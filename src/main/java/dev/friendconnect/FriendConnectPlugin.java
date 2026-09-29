package dev.friendconnect;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class FriendConnectPlugin extends JavaPlugin implements Listener {

    private NodeRunner runner;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        runner = new NodeRunner(this);

        FriendConnectCommand handler = new FriendConnectCommand(this, runner);
        PluginCommand command = getCommand("friendconnect");
        if (command != null) {
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getServer().getPluginManager().registerEvents(this, this);

        if (getConfig().getBoolean("plugin.auto-start", true)) {
            runner.start();
        } else {
            getLogger().info("auto-start is off. Use /friendconnect start when you are ready.");
        }
    }

    @Override
    public void onDisable() {
        if (runner != null) {
            runner.stopAndWait();
        }
    }

    /** Remind admins who join while a Microsoft sign-in is still pending. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (runner.state() != NodeRunner.State.WAITING_FOR_LOGIN) return;
        if (!event.getPlayer().hasPermission("friendconnect.admin")) return;
        getServer().getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) runner.sendAuthPrompt(event.getPlayer());
        }, 40L);
    }
}
