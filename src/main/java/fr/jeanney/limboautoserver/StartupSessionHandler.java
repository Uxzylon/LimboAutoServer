package fr.jeanney.limboautoserver;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.concurrent.TimeUnit;

/**
 * Session handler for a player held in the limbo world while their backend
 * server boots. On spawn it triggers (or joins) the server startup, optionally
 * shows a progress boss bar, then transfers the player to the real server once
 * it is responsive.
 */
public class StartupSessionHandler implements LimboSessionHandler {
    private final LimboAutoServer plugin;
    private final PluginLogger logger;
    private final Player player;
    private final RegisteredServer target;
    private final String targetName;

    private LimboPlayer limboPlayer;
    private BossBar bossBar;
    private ScheduledTask progressTask;
    private long startedAt;
    private boolean transferring;
    private volatile boolean finished;

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
        this.startedAt = System.currentTimeMillis();
        limboPlayer.disableFalling();

        ServerManager serverManager = plugin.getServerManager();
        serverManager.cancelShutdownServer(target);

        Messenger.send(player, plugin.getConfig().getMessage("starting").orElse(null), targetName);
        startProgressBar();
        logger.info("Holding {} in limbo while {} starts.", player.getUsername(), targetName);

        serverManager.ensureServerStarted(target).whenComplete((result, ex) -> {
            stopProgressBar();
            if (!player.isActive()) {
                // Player already left the limbo (e.g. quit); nothing to transfer.
                logger.debug("{} is no longer connected; skipping transfer to {}.", player.getUsername(), targetName);
                return;
            }
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
        if (transferring || !player.isActive()) {
            return;
        }
        transferring = true;
        Messenger.send(player, plugin.getConfig().getMessage("notify").orElse(null), targetName);
        logger.info("Transferring {} from limbo to {}.", player.getUsername(), targetName);
        // Hand the player over to the now-online backend server.
        limboPlayer.disconnect(target);
    }

    // ----- progress bar -----

    private void startProgressBar() {
        if (!plugin.getConfig().isProgressBarEnabled()) {
            return;
        }
        bossBar = BossBar.bossBar(progressTitle(0), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        player.showBossBar(bossBar);

        long estMillis = Math.max(1000L, plugin.getConfig().getEstimatedStartupSeconds() * 1000L);
        progressTask = plugin.getProxy().getScheduler()
                .buildTask(plugin, () -> {
                    if (finished || bossBar == null || !player.isActive()) {
                        return;
                    }
                    long elapsed = System.currentTimeMillis() - startedAt;
                    // Cap at 95% so the bar visibly completes only once the server is actually up.
                    float progress = Math.min(0.95f, (float) elapsed / estMillis);
                    bossBar.progress(progress);
                    bossBar.name(progressTitle(elapsed / 1000));
                })
                .repeat(1, TimeUnit.SECONDS)
                .schedule();
    }

    private void stopProgressBar() {
        finished = true;
        if (progressTask != null) {
            progressTask.cancel();
            progressTask = null;
        }
        if (bossBar == null) {
            return;
        }
        BossBar bar = bossBar;
        if (player.isActive() && !transferring) {
            // Briefly show a completed bar before the transfer happens.
            bar.progress(1f);
            bar.color(BossBar.Color.GREEN);
            bar.name(Messenger.deserialize(
                    plugin.getConfig().getProgressReadyTitle().replace("%serverName%", targetName)));
        }
        player.hideBossBar(bar);
        bossBar = null;
    }

    private Component progressTitle(long elapsedSeconds) {
        return Messenger.deserialize(plugin.getConfig().getProgressTitle()
                .replace("%serverName%", targetName)
                .replace("%time%", String.valueOf(elapsedSeconds)));
    }

    @Override
    public void onDisconnect() {
        stopProgressBar();
        // Player left the limbo (either transferred or quit). If they quit before
        // the server finished booting and nobody else is waiting, schedule shutdown.
        if (!transferring && target.getPlayersConnected().isEmpty()
                && !plugin.getServerManager().isStarting(target)) {
            plugin.getServerManager().scheduleShutdownServer(target);
        }
    }
}
