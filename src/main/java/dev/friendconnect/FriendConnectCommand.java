package dev.friendconnect;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public final class FriendConnectCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("status", "start", "stop", "restart", "reload", "auth");

    private final FriendConnectPlugin plugin;
    private final NodeRunner runner;

    public FriendConnectCommand(FriendConnectPlugin plugin, NodeRunner runner) {
        this.plugin = plugin;
        this.runner = runner;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "auth" -> {
                if (runner.authUrl() == null) {
                    say(sender, "No Microsoft sign-in is pending.", NamedTextColor.GRAY);
                } else {
                    runner.sendAuthPrompt(sender);
                }
            }
            case "start" -> {
                if (runner.isActive()) {
                    say(sender, "Already running. Use /" + label + " restart to restart it.", NamedTextColor.YELLOW);
                } else {
                    plugin.reloadConfig();
                    runner.start();
                    say(sender, "Starting... watch the console for progress.", NamedTextColor.GREEN);
                }
            }
            case "stop" -> {
                say(sender, "Stopping...", NamedTextColor.YELLOW);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    runner.stopAndWait();
                    Bukkit.getScheduler().runTask(plugin, () -> say(sender, "Stopped.", NamedTextColor.GREEN));
                });
            }
            case "restart", "reload" -> {
                plugin.reloadConfig();
                Settings settings = Settings.from(plugin.getConfig());
                say(sender, "Config reloaded. Restarting Friend Connect...", NamedTextColor.YELLOW);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    runner.stopAndWait();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        runner.start(settings);
                        say(sender, "Started. Use /" + label + " status to check on it.", NamedTextColor.GREEN);
                    });
                });
            }
            default -> say(sender, "Usage: /" + label + " <status|start|stop|restart|reload|auth>", NamedTextColor.RED);
        }
        return true;
    }

    private void status(CommandSender sender) {
        NodeRunner.State st = runner.state();
        TextColor color = switch (st) {
            case RUNNING -> NamedTextColor.GREEN;
            case ERROR -> NamedTextColor.RED;
            case STOPPED -> NamedTextColor.GRAY;
            default -> NamedTextColor.YELLOW;
        };
        say(sender, "Status: " + st.name().replace('_', ' '), color);
        if (runner.gamertag() != null) {
            say(sender, "Host account: " + runner.gamertag() + " (players add this account as a friend)", NamedTextColor.GRAY);
            say(sender, "Since start: " + runner.friendsAdded() + " friends added, "
                    + runner.redirected() + " players redirected", NamedTextColor.GRAY);
        }
        if (st == NodeRunner.State.WAITING_FOR_LOGIN) runner.sendAuthPrompt(sender);
        if (runner.lastError() != null && st == NodeRunner.State.ERROR) {
            say(sender, "Last error: " + runner.lastError(), NamedTextColor.RED);
        }
    }

    private static void say(CommandSender to, String message, TextColor color) {
        to.sendMessage(Component.text("[FriendConnect] ", NamedTextColor.AQUA)
                .append(Component.text(message, color)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUB.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
