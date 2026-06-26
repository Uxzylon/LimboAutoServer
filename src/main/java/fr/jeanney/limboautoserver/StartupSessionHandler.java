package fr.jeanney.limboautoserver;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.concurrent.TimeUnit;

/**
 * Session handler for a player held in the limbo world while their backend
 * server boots. On spawn it triggers (or joins) the server startup, then
 * transfers the player to the real server once it is responsive.
 */
public class StartupSessionHandler implements LimboSessionHandler {
    private final LimboAutoServer plugin;
    private final PluginLogger logger;
    private final Player player;
    private final RegisteredServer target;
    private final String targetName;

    private LimboPlayer limboPlayer;
    private boolean transferring;

    public StartupSessionHandler(LimboAutoServer plugin, Player player, RegisteredServer target) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.player = player;
        this.target = target;
        this.targetName = target.getServerInfo().getName();
    }

    @Override
    public void onSpawn(Limbo server, LimboPlayer limboPlayer) {
        this.limboPlayer = limboPlayer;
        limboPlayer.disableFalling();

        ServerManager serverManager = plugin.getServerManager();
        serverManager.cancelShutdownServer(target);

        Messenger.send(player, plugin.getConfig().getMessage("starting").orElse(null), targetName);
        logger.info("Holding {} in limbo while {} starts.", player.getUsername(), targetName);

        serverManager.ensureServerStarted(target).whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("Server {} failed to start for {}: {}", targetName, player.getUsername(), ex.getMessage());
                Messenger.send(player, plugin.getConfig().getMessage("failed").orElse(null), targetName);
                limboPlayer.getScheduledExecutor().schedule(
                        () -> player.disconnect(Component.text("Failed to start server " + targetName)
                                .color(NamedTextColor.RED)),
                        3, TimeUnit.SECONDS);
                return;
            }
            transferToServer();
        });
    }

    private void transferToServer() {
        if (transferring) {
            return;
        }
        transferring = true;
        Messenger.send(player, plugin.getConfig().getMessage("notify").orElse(null), targetName);
        logger.info("Transferring {} from limbo to {}.", player.getUsername(), targetName);
        // Hand the player over to the now-online backend server.
        limboPlayer.disconnect(target);
    }

    @Override
    public void onDisconnect() {
        // Player left the limbo (either transferred or quit). If they quit before
        // the server finished booting and nobody else is waiting, schedule shutdown.
        if (!transferring && target.getPlayersConnected().isEmpty()
                && !plugin.getServerManager().isStarting(target)) {
            plugin.getServerManager().scheduleShutdownServer(target);
        }
    }
}
