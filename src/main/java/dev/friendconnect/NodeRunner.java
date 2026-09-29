package dev.friendconnect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the bundled Friend Connect (Node.js) script as a child process of the
 * Minecraft server and keeps it alive.
 */
public final class NodeRunner {

    public enum State { STOPPED, INSTALLING, STARTING, WAITING_FOR_LOGIN, RUNNING, ERROR }

    private enum Result { STOPPED, SETUP_FAILED, EXITED }

    /** Files copied from the jar into plugins/FriendConnect/runtime/ on every start. */
    private static final String[] BUNDLED_FILES = {
            "package.json",
            "dist/main.js",
            "dist/modules/portal.js",
            "dist/modules/showcase.js",
            "dist/types/fileManager.js",
            "dist/utils/fileManager.js"
    };

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    private static final Pattern AUTH_LINE = Pattern.compile("Open (\\S+) and enter the code (\\S+)");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final FriendConnectPlugin plugin;
    private final Path runtimeDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private volatile State state = State.STOPPED;
    private volatile Process currentProcess;
    private volatile Thread worker;
    private volatile boolean stopRequested;
    private volatile Settings settings;

    private volatile String gamertag;
    private volatile String authUrl;
    private volatile String authCode;
    private volatile String lastError;
    private final AtomicInteger friendsAdded = new AtomicInteger();
    private final AtomicInteger redirected = new AtomicInteger();

    public NodeRunner(FriendConnectPlugin plugin) {
        this.plugin = plugin;
        this.runtimeDir = plugin.getDataFolder().toPath().resolve("runtime");
    }

    // ------------------------------------------------------------------ public API

    public State state() { return state; }
    public String gamertag() { return gamertag; }
    public String authUrl() { return authUrl; }
    public String authCode() { return authCode; }
    public String lastError() { return lastError; }
    public int friendsAdded() { return friendsAdded.get(); }
    public int redirected() { return redirected.get(); }

    public boolean isActive() {
        Thread w = worker;
        return w != null && w.isAlive();
    }

    /** Reads config.yml (call from the main thread) and starts. */
    public void start() {
        start(Settings.from(plugin.getConfig()));
    }

    public synchronized void start(Settings s) {
        if (isActive()) {
            plugin.getLogger().info("Friend Connect is already running.");
            return;
        }
        this.settings = s;
        this.stopRequested = false;
        this.lastError = null;
        this.authUrl = null;
        this.authCode = null;
        this.gamertag = null;

        String problem = s.validate();
        if (problem != null) {
            fail(problem);
            return;
        }
        if (s.looksLocal()) {
            plugin.getLogger().warning("server.ip is \"" + s.ip() + "\". Players on other devices cannot reach that - "
                    + "use your public address or domain.");
        }

        Thread t = new Thread(() -> runLoop(s), "FriendConnect-Runner");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Stops the process and waits (max ~8s). Safe to call from any thread. */
    public synchronized void stopAndWait() {
        stopRequested = true;
        Process p = currentProcess;
        if (p != null) {
            p.destroy();
        }
        Thread w = worker;
        if (w != null) {
            w.interrupt();
            try {
                w.join(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Process p2 = currentProcess;
        if (p2 != null && p2.isAlive()) {
            p2.destroyForcibly();
        }
        worker = null;
        currentProcess = null;
        state = State.STOPPED;
    }

    // ------------------------------------------------------------------ main loop

    private void runLoop(Settings s) {
        int attempts = 0;
        try {
            while (!stopRequested) {
                Result result = runOnce(s);
                if (stopRequested || result == Result.STOPPED) {
                    break;
                }
                if (result == Result.SETUP_FAILED) {
                    state = State.ERROR;
                    break;
                }

                // The process exited on its own.
                if (state == State.RUNNING) {
                    attempts = 0; // it was healthy for a while, so start counting again
                }
                state = State.ERROR;

                if (!s.autoRestart()) {
                    plugin.getLogger().warning("Friend Connect stopped. Auto-restart is off; use /friendconnect start.");
                    break;
                }
                attempts++;
                if (s.maxRestartAttempts() > 0 && attempts > s.maxRestartAttempts()) {
                    plugin.getLogger().severe("Friend Connect failed " + s.maxRestartAttempts()
                            + " times in a row - giving up. Fix the problem above, then run /friendconnect restart.");
                    break;
                }
                plugin.getLogger().warning("Friend Connect stopped unexpectedly. Restarting in "
                        + s.restartDelaySeconds() + "s (attempt " + attempts
                        + (s.maxRestartAttempts() > 0 ? "/" + s.maxRestartAttempts() : "") + ")...");
                try {
                    Thread.sleep(s.restartDelaySeconds() * 1000L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Friend Connect crashed unexpectedly", t);
            lastError = String.valueOf(t.getMessage());
            state = State.ERROR;
        } finally {
            if (stopRequested) {
                state = State.STOPPED;
            }
        }
    }

    private Result runOnce(Settings s) {
        state = State.STARTING;

        try {
            prepareRuntime(s);
        } catch (IOException e) {
            return setupFailed("Could not write files to " + runtimeDir + ": " + e.getMessage());
        }

        Toolchain tc = resolveToolchain(s);
        if (tc == null) {
            return stopRequested ? Result.STOPPED : Result.SETUP_FAILED;
        }
        if (stopRequested) return Result.STOPPED;

        if (!ensureDependencies(s, tc)) {
            return stopRequested ? Result.STOPPED : Result.SETUP_FAILED;
        }
        if (stopRequested) return Result.STOPPED;

        plugin.getLogger().info("Starting Friend Connect (Node " + tc.version() + ")...");

        ProcessBuilder pb = new ProcessBuilder(tc.node(), "dist/main.js", "lib/config.json");
        tc.applyPath(pb);
        pb.directory(runtimeDir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("NODE_NO_WARNINGS", "1");

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return setupFailed("Could not start Node.js: " + e.getMessage());
        }
        currentProcess = p;

        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = ANSI.matcher(line).replaceAll("").trim();
                if (line.isEmpty()) continue;
                tail.addLast(line);
                if (tail.size() > 25) tail.removeFirst();
                handleLine(s, line);
            }
        } catch (IOException ignored) {
            // stream closed because the process ended
        }

        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            currentProcess = null;
            return Result.STOPPED;
        }
        currentProcess = null;

        if (stopRequested) return Result.STOPPED;

        plugin.getLogger().warning("Friend Connect process exited with code " + exit + ".");
        if (!s.debug() && exit != 0 && lastError == null) {
            plugin.getLogger().warning("Last output:");
            tail.forEach(l -> plugin.getLogger().warning("  " + l));
        }
        return Result.EXITED;
    }

    private Result setupFailed(String message) {
        fail(message);
        return Result.SETUP_FAILED;
    }

    private void fail(String message) {
        lastError = message;
        state = State.ERROR;
        plugin.getLogger().severe(message);
    }

    // ------------------------------------------------------------------ output handling

    private void handleLine(Settings s, String line) {
        if (line.startsWith("[FC-AUTH]")) {
            state = State.WAITING_FOR_LOGIN;
            Matcher m = AUTH_LINE.matcher(line);
            if (m.find()) {
                authUrl = m.group(1);
                authCode = m.group(2);
            }
            logAuthBanner();
            notifyAdmins(s);
        } else if (line.startsWith("[FC-READY]")) {
            state = State.RUNNING;
            authUrl = null;
            authCode = null;
            int idx = line.lastIndexOf(':');
            gamertag = idx >= 0 ? line.substring(idx + 1).trim() : null;
            plugin.getLogger().info("Friend Connect is running as \"" + gamertag + "\". "
                    + "Players can now add this account on Xbox and join from their friends list.");
        } else if (line.startsWith("[FC-ERROR]")) {
            lastError = line.substring("[FC-ERROR]".length()).trim();
            plugin.getLogger().severe("Friend Connect error: " + lastError);
        } else if (line.startsWith("Friend added:")) {
            friendsAdded.incrementAndGet();
            plugin.getLogger().info(line);
        } else if (line.startsWith("Player redirected:")) {
            redirected.incrementAndGet();
            plugin.getLogger().info(line);
        } else if (line.startsWith("Player joined")) {
            plugin.getLogger().info(line);
        } else if (line.startsWith("[FC-IMAGE-WARN]")) {
            plugin.getLogger().warning("Custom image: " + line.substring("[FC-IMAGE-WARN]".length()).trim());
        } else if (line.startsWith("[FC-IMAGE]")) {
            plugin.getLogger().info("Custom image: " + line.substring("[FC-IMAGE]".length()).trim());
        } else if (s.debug()) {
            plugin.getLogger().info("[node] " + line);
        }
    }

    private void logAuthBanner() {
        String url = authUrl != null ? authUrl : "https://www.microsoft.com/link";
        String code = authCode != null ? authCode : "(see above)";
        plugin.getLogger().warning("=====================================================");
        plugin.getLogger().warning(" MICROSOFT SIGN-IN REQUIRED (one time only)");
        plugin.getLogger().warning(" 1. Open:  " + url);
        plugin.getLogger().warning(" 2. Enter: " + code);
        plugin.getLogger().warning(" 3. Sign in with the HOST Microsoft account");
        plugin.getLogger().warning("=====================================================");
    }

    private void notifyAdmins(Settings s) {
        if (!s.notifyAdmins() || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("friendconnect.admin")) sendAuthPrompt(p);
            }
        });
    }

    /** Sends the sign-in instructions to a player (main thread). */
    public void sendAuthPrompt(org.bukkit.command.CommandSender to) {
        if (authUrl == null || authCode == null) return;
        Component prefix = Component.text("[FriendConnect] ", NamedTextColor.AQUA);
        to.sendMessage(prefix.append(Component.text("Microsoft sign-in needed for the host account.", NamedTextColor.YELLOW)));
        to.sendMessage(prefix.append(Component.text("Open ", NamedTextColor.GRAY))
                .append(Component.text(authUrl, NamedTextColor.GREEN).clickEvent(ClickEvent.openUrl(authUrl)))
                .append(Component.text(" and enter code ", NamedTextColor.GRAY))
                .append(Component.text(authCode, NamedTextColor.GOLD)));
    }

    // ------------------------------------------------------------------ Node.js toolchain

    /** The node + npm commands to use (system-wide install, or the private downloaded copy). */
    private record Toolchain(String node, List<String> npmBase, Path extraPath, String version) {
        List<String> npm(String... args) {
            List<String> cmd = new java.util.ArrayList<>(npmBase);
            cmd.addAll(List.of(args));
            return cmd;
        }

        /** Makes the chosen node findable by npm's install scripts. */
        void applyPath(ProcessBuilder pb) {
            if (extraPath == null) return;
            String key = WINDOWS ? "Path" : "PATH";
            for (String k : pb.environment().keySet()) {
                if (k.equalsIgnoreCase("PATH")) {
                    key = k;
                    break;
                }
            }
            String old = pb.environment().getOrDefault(key, "");
            pb.environment().put(key, extraPath + java.io.File.pathSeparator + old);
        }
    }

    /** Uses the system Node.js if it works, otherwise (optionally) downloads a private copy. */
    private Toolchain resolveToolchain(Settings s) {
        String problem;
        String versionOut = captureOutput(List.of(s.nodePath(), "--version"));
        if (versionOut != null && nodeVersionOk(versionOut)) {
            String npm = s.npmPath();
            if (WINDOWS && !npm.contains("/") && !npm.contains("\\")
                    && !npm.toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                npm = npm + ".cmd";
            }
            return new Toolchain(s.nodePath(), List.of(npm), null, versionOut.trim());
        }
        if (versionOut == null) {
            problem = "Could not run \"" + s.nodePath() + "\" (Node.js is not installed here).";
        } else {
            problem = "Node.js " + versionOut.trim() + " is too old (18.20 or newer is needed).";
        }

        if (!s.autoDownloadNode()) {
            fail(problem + " Install Node.js, set plugin.node-path, or set plugin.auto-download-node: true.");
            return null;
        }

        plugin.getLogger().info(problem + " Using a private copy instead.");
        try {
            LocalNode.Paths paths = new LocalNode(plugin.getDataFolder().toPath().resolve("node-runtime"),
                    plugin.getLogger()).ensure();
            String node = paths.node().toAbsolutePath().toString();
            String check = captureOutput(List.of(node, "--version"));
            if (check == null) {
                fail("The downloaded Node.js could not be started on this system (" + node + "). "
                        + "If your host blocks running downloaded programs or uses Alpine/musl Linux, "
                        + "ask them to install Node.js.");
                return null;
            }
            return new Toolchain(node, List.of(node, paths.npmCli().toAbsolutePath().toString()),
                    paths.node().toAbsolutePath().getParent(), check.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            if (!stopRequested) {
                fail("Could not download Node.js automatically: " + e.getMessage()
                        + ". The server needs access to registry.npmjs.org, or install Node.js manually.");
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ setup helpers

    private void prepareRuntime(Settings s) throws IOException {
        Files.createDirectories(runtimeDir.resolve("lib"));
        for (String name : BUNDLED_FILES) {
            try (InputStream in = plugin.getResource("node/" + name)) {
                if (in == null) throw new IOException("Missing bundled file: node/" + name);
                Path target = runtimeDir.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ip", s.ip());
        json.put("port", s.port());
        json.put("hostName", s.hostName());
        json.put("levelName", s.levelName());
        json.put("joinability", s.joinability());
        json.put("autoAcceptFriends", s.autoAcceptFriends());
        json.put("autoAddFriends", s.autoAddFriends());
        json.put("updatePresence", s.updatePresence());
        json.put("worldVersion", s.worldVersion());
        json.put("maxPlayers", s.maxPlayers());
        json.put("imagePath", s.imageFile().isBlank() ? ""
                : plugin.getDataFolder().toPath().resolve(s.imageFile()).toAbsolutePath().toString());
        Files.writeString(runtimeDir.resolve("lib/config.json"), gson.toJson(json), StandardCharsets.UTF_8);
    }

    private boolean ensureDependencies(Settings s, Toolchain tc) {
        Path marker = runtimeDir.resolve(".deps-installed");
        String wanted;
        try {
            wanted = Integer.toHexString(Files.readString(runtimeDir.resolve("package.json")).hashCode());
        } catch (IOException e) {
            fail("Could not read package.json: " + e.getMessage());
            return false;
        }

        boolean installed = Files.isDirectory(runtimeDir.resolve("node_modules/bedrock-portal"));
        try {
            installed = installed && Files.exists(marker) && Files.readString(marker).trim().equals(wanted);
        } catch (IOException e) {
            installed = false;
        }
        if (installed) return true;

        if (!s.autoInstall()) {
            fail("Dependencies are not installed. Set plugin.auto-install-dependencies: true, or run "
                    + "\"npm install --ignore-scripts && npm rebuild node-datachannel\" inside " + runtimeDir);
            return false;
        }

        state = State.INSTALLING;
        plugin.getLogger().info("Installing Friend Connect dependencies (first start only, may take a minute)...");
        // --ignore-scripts skips raknet-native's compile step (not needed);
        // node-datachannel is then rebuilt on its own because it needs to fetch its prebuilt binary.
        int code = runAndLog(tc.npm("install", "--ignore-scripts", "--omit=dev", "--no-audit",
                "--no-fund", "--no-package-lock"), tc, s);
        if (code == 0 && !stopRequested) {
            code = runAndLog(tc.npm("rebuild", "node-datachannel"), tc, s);
        }
        if (stopRequested) return false;
        if (code != 0) {
            fail("Installing dependencies failed (exit code " + code + "). Check that this machine has internet "
                    + "access to registry.npmjs.org and github.com.");
            return false;
        }
        try {
            Files.writeString(marker, wanted);
        } catch (IOException ignored) {
        }
        plugin.getLogger().info("Dependencies installed.");
        return true;
    }

    /** Runs a command in the runtime dir, logging its output. Returns the exit code, or -1 on failure. */
    private int runAndLog(List<String> cmd, Toolchain tc, Settings s) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        tc.applyPath(pb);
        pb.directory(runtimeDir.toFile());
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            plugin.getLogger().severe("Could not run \"" + cmd.get(0) + "\": " + e.getMessage());
            return -1;
        }
        currentProcess = p;
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = ANSI.matcher(line).replaceAll("").trim();
                if (line.isEmpty()) continue;
                tail.addLast(line);
                if (tail.size() > 20) tail.removeFirst();
                if (s.debug()) plugin.getLogger().info("[npm] " + line);
            }
            int exit = p.waitFor();
            if (exit != 0 && !s.debug() && !stopRequested) {
                tail.forEach(l -> plugin.getLogger().warning("[npm] " + l));
            }
            return exit;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            p.destroyForcibly();
            return -1;
        } finally {
            currentProcess = null;
        }
    }

    /** Runs a short command and returns its output, or null if it could not be run / failed. */
    private String captureOutput(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static boolean nodeVersionOk(String versionOutput) {
        Matcher m = Pattern.compile("v?(\\d+)\\.(\\d+)").matcher(versionOutput.trim());
        if (!m.find()) return true; // unknown format, don't block
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        return major > 18 || (major == 18 && minor >= 20);
    }
}
