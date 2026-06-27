package fr.jeanney.limboautoserver;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.player.GameMode;

/**
 * Owns the LimboAPI virtual world/server that players wait in while their
 * backend boots, and handles spawning players into it.
 */
public class LimboManager {
    private final LimboAutoServer plugin;
    private final PluginLogger logger;
    private final LimboFactory factory;

    private Limbo limbo;

    public LimboManager(LimboAutoServer plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.factory = (LimboFactory) plugin.getProxy().getPluginManager()
                .getPlugin("limboapi")
                .flatMap(PluginContainer::getInstance)
                .orElseThrow(() -> new IllegalStateException("LimboAPI plugin is not loaded."));
    }

    /**
     * (Re)builds the limbo virtual server from the current configuration.
     */
    public void rebuild() {
        Configuration config = plugin.getConfig();

        Dimension dimension;
        try {
            dimension = Dimension.valueOf(config.getLimboDimension());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown limbo dimension '{}', falling back to OVERWORLD.", config.getLimboDimension());
            dimension = Dimension.OVERWORLD;
        }

        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(config.getLimboGameMode());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown limbo gameMode '{}', falling back to ADVENTURE.", config.getLimboGameMode());
            gameMode = GameMode.ADVENTURE;
        }

        VirtualWorld world = factory.createVirtualWorld(
                dimension,
                config.getLimboPosX(), config.getLimboPosY(), config.getLimboPosZ(),
                (float) config.getLimboYaw(), (float) config.getLimboPitch());

        Limbo newLimbo = factory.createLimbo(world)
                .setName("LimboAutoServer")
                .setWorldTime(config.getLimboWorldTime())
                .setGameMode(gameMode)
                // Independent of Velocity's global read-timeout: LimboAPI sends a
                // keep-alive every readTimeout/2 ms, which must stay under the
                // client's 30s timeout or players get "Timed out" while waiting.
                .setReadTimeout(config.getLimboReadTimeout());

        Limbo previous = this.limbo;
        this.limbo = newLimbo;
        if (previous != null) {
            previous.dispose();
        }
        logger.info("Limbo world ready (dimension={}, gameMode={}).", dimension, gameMode);
    }

    /**
     * Spawns the player into the limbo world and kicks off the backend startup.
     */
    public void spawnIntoLimbo(Player player, RegisteredServer target) {
        if (limbo == null) {
            logger.error("Limbo not initialized; cannot hold player {}.", player.getUsername());
            return;
        }
        limbo.spawnPlayer(player, new StartupSessionHandler(plugin, player, target));
    }

    public void dispose() {
        if (limbo != null) {
            limbo.dispose();
            limbo = null;
        }
    }
}
