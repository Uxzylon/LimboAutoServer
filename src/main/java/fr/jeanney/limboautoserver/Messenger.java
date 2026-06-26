package fr.jeanney.limboautoserver;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Helper for sending MiniMessage-formatted chat messages to players.
 *
 * <p>Adapted from AutoServer (MIT, Artificial-720).</p>
 */
public final class Messenger {

    private Messenger() {
    }

    public static void send(Player player, String message) {
        send(player, message, null);
    }

    public static void send(Player player, String message, String serverName) {
        if (message == null) {
            return;
        }
        if (serverName != null) {
            message = message.replace("%serverName%", serverName);
        }
        player.sendMessage(deserialize(message));
    }

    public static void send(Player player, String message, long seconds) {
        if (message == null) {
            return;
        }
        message = message.replace("%time%", String.valueOf(seconds));
        player.sendMessage(deserialize(message));
    }

    public static Component deserialize(String message) {
        return MiniMessage.miniMessage().deserialize(message);
    }
}
