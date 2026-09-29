package dev.friendconnect;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Downloads a private copy of Node.js + npm into the plugin folder, for servers where Node.js
 * cannot be installed. Files come from registry.npmjs.org (the "node-linux-x64" style packages
 * and the "npm" package) - the same host the plugin already needs for its dependencies.
 */
final class LocalNode {

    static final String NODE_VERSION = "22.22.2";
    static final String NPM_VERSION = "10.9.4";

    record Paths(Path node, Path npmCli) {}

    private final Path dir;
    private final Logger log;

    LocalNode(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    /** Returns the local node/npm paths, downloading them first if needed. */
    Paths ensure() throws IOException, InterruptedException {
        String platform = platform();
        boolean windows = platform.startsWith("win-");
        Path node = dir.resolve("node").resolve("bin").resolve(windows ? "node.exe" : "node");
        Path npmCli = dir.resolve("npm").resolve("bin").resolve("npm-cli.js");
        Path marker = dir.resolve(".installed");
        String wanted = NODE_VERSION + "/" + NPM_VERSION + "/" + platform;

        if (Files.isRegularFile(node) && Files.isRegularFile(npmCli) && Files.exists(marker)
                && Files.readString(marker).trim().equals(wanted)) {
            return new Paths(node, npmCli);
        }

        log.info("Node.js was not found on this machine - downloading a private copy (about 50 MB, one time)...");
        Files.createDirectories(dir);

        String nodeUrl = "https://registry.npmjs.org/node-" + platform + "/-/node-" + platform + "-" + NODE_VERSION + ".tgz";
        String npmUrl = "https://registry.npmjs.org/npm/-/npm-" + NPM_VERSION + ".tgz";

        Path nodeTmp = Files.createTempFile(dir, "node-", ".tgz");
        Path npmTmp = Files.createTempFile(dir, "npm-", ".tgz");
        try {
            download(nodeUrl, nodeTmp);
            log.info("Extracting Node.js...");
            deleteRecursively(dir.resolve("node"));
            extractTgz(nodeTmp, dir.resolve("node"),
                    n -> n.equals("bin/node") || n.equals("bin/node.exe") || n.equals("LICENSE"));

            log.info("Downloading npm...");
            download(npmUrl, npmTmp);
            deleteRecursively(dir.resolve("npm"));
            extractTgz(npmTmp, dir.resolve("npm"), n -> true);
        } finally {
            Files.deleteIfExists(nodeTmp);
            Files.deleteIfExists(npmTmp);
        }

        if (!Files.isRegularFile(node) || !Files.isRegularFile(npmCli)) {
            throw new IOException("Downloaded files were incomplete.");
        }
        if (!node.toFile().setExecutable(true)) {
            log.warning("Could not mark " + node + " as executable; if it fails to start, run: chmod +x " + node);
        }
        Files.writeString(marker, wanted);
        log.info("Private Node.js " + NODE_VERSION + " installed in " + dir);
        return new Paths(node, npmCli);
    }

    // ------------------------------------------------------------------ helpers

    static String platform() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o;
        if (os.contains("win")) o = "win";
        else if (os.contains("mac") || os.contains("darwin")) o = "darwin";
        else if (os.contains("linux")) o = "linux";
        else throw new IOException("Unsupported operating system: " + os);
        String a;
        if (arch.equals("amd64") || arch.equals("x86_64")) a = "x64";
        else if (arch.equals("aarch64") || arch.equals("arm64")) a = "arm64";
        else throw new IOException("Unsupported CPU architecture: " + arch);
        if (o.equals("win") && a.equals("arm64")) {
            throw new IOException("Windows on ARM is not supported by the automatic download; install Node.js manually.");
        }
        return o + "-" + a;
    }

    private static void download(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "FriendConnect-Paper-Plugin")
                .GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed (HTTP " + response.statusCode() + ") for " + url);
        }
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ minimal tar.gz reader

    /**
     * Extracts a .tgz produced by "npm pack". The first path component ("package/") is removed.
     * Only entries whose stripped name passes {@code accept} are written.
     */
    static void extractTgz(Path tgz, Path dest, Predicate<String> accept) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(tgz), 1 << 16))) {
            byte[] header = new byte[512];
            String paxPath = null;
            String longName = null;

            while (in.readNBytes(header, 0, 512) == 512) {
                if (isZeroBlock(header)) break;

                String name = cstr(header, 0, 100);
                String prefix = cstr(header, 345, 155);
                if (!prefix.isEmpty() && cstr(header, 257, 5).equals("ustar")) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                byte type = header[156];
                long padding = (512 - (size % 512)) % 512;

                if (type == 'x') {                       // pax header: may carry a long "path"
                    byte[] data = in.readNBytes((int) size);
                    in.skipNBytes(padding);
                    paxPath = paxValue(data, "path");
                    continue;
                }
                if (type == 'L') {                       // GNU long name
                    byte[] data = in.readNBytes((int) size);
                    in.skipNBytes(padding);
                    longName = cstr(data, 0, data.length);
                    continue;
                }
                if (type == 'g') {                       // global pax header, ignore
                    in.skipNBytes(size + padding);
                    continue;
                }

                String entry = paxPath != null ? paxPath : (longName != null ? longName : name);
                paxPath = null;
                longName = null;

                boolean regular = type == '0' || type == 0;
                String rel = stripFirst(entry);
                if (regular && rel != null && accept.test(rel)) {
                    Path target = root.resolve(rel).normalize();
                    if (!target.startsWith(root)) {      // path traversal guard
                        in.skipNBytes(size + padding);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        long left = size;
                        byte[] buf = new byte[1 << 16];
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("Unexpected end of archive");
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    in.skipNBytes(padding);
                } else {
                    in.skipNBytes(size + padding);
                }
            }
        }
    }

    private static String stripFirst(String name) {
        int i = name.indexOf('/');
        if (i < 0 || i == name.length() - 1) return null;
        return name.substring(i + 1);
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        String s = cstr(b, off, len).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    /** Reads one key from a pax extended header ("<len> key=value\n" records). */
    private static String paxValue(byte[] data, String key) {
        int pos = 0;
        while (pos < data.length) {
            int sp = pos;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, pos, sp - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || pos + len > data.length) break;
            String record = new String(data, sp + 1, pos + len - sp - 2, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq > 0 && record.substring(0, eq).equals(key)) return record.substring(eq + 1);
            pos += len;
        }
        return null;
    }
}
