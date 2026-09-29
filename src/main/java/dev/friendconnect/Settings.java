package dev.friendconnect;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Set;

/** Immutable snapshot of config.yml (safe to read from the background thread). */
public record Settings(
        String ip, int port,
        String hostName, String levelName, String worldVersion, int maxPlayers, String imageFile,
        String joinability, boolean autoAcceptFriends, boolean autoAddFriends, boolean updatePresence,
        boolean autoInstall, boolean autoDownloadNode, String nodePath, String npmPath,
        boolean autoRestart, int restartDelaySeconds, int maxRestartAttempts,
        boolean notifyAdmins, boolean debug) {

    private static final Set<String> JOINABILITY = Set.of("invite_only", "friends_only", "friends_of_friends");

    public static Settings from(FileConfiguration c) {
        return new Settings(
                c.getString("server.ip", "CHANGE_ME").trim(),
                c.getInt("server.port", 19132),
                c.getString("world.host-name", "My Server"),
                c.getString("world.level-name", "Tap to join!"),
                c.getString("world.version", "1.21"),
                c.getInt("world.max-players", 100),
                c.getString("world.image", "screenshot.jpg").trim(),
                c.getString("friend-connect.joinability", "friends_of_friends").trim().toLowerCase(Locale.ROOT),
                c.getBoolean("friend-connect.auto-accept-friends", true),
                c.getBoolean("friend-connect.auto-add-friends", true),
                c.getBoolean("friend-connect.update-presence", true),
                c.getBoolean("plugin.auto-install-dependencies", true),
                c.getBoolean("plugin.auto-download-node", true),
                c.getString("plugin.node-path", "node"),
                c.getString("plugin.npm-path", "npm"),
                c.getBoolean("plugin.auto-restart", true),
                Math.max(5, c.getInt("plugin.restart-delay-seconds", 30)),
                Math.max(0, c.getInt("plugin.max-restart-attempts", 5)),
                c.getBoolean("plugin.notify-admins-in-game", true),
                c.getBoolean("plugin.debug", false));
    }

    /** @return a human readable problem, or null if the settings are usable. */
    public String validate() {
        if (ip.isEmpty() || ip.equalsIgnoreCase("CHANGE_ME") || ip.equalsIgnoreCase("CHANGE ME")) {
            return "server.ip is not set. Open plugins/FriendConnect/config.yml, set server.ip to your public "
                    + "Bedrock/Geyser address, then run /friendconnect reload";
        }
        if (port < 1 || port > 65535) {
            return "server.port must be between 1 and 65535";
        }
        if (!JOINABILITY.contains(joinability)) {
            return "friend-connect.joinability must be invite_only, friends_only or friends_of_friends";
        }
        return null;
    }

    public boolean looksLocal() {
        String h = ip.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("0.0.0.0");
    }
}
