package fr.jeanney.limboautoserver;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.jeanney.limboautoserver.commands.LimboAutoServerCommand;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "limboautoserver",
        name = "LimboAutoServer",
        version = "1.0.0",
        description = "Lazy-starts backend servers and holds players in a LimboAPI virtual world while the server boots, preventing login timeouts.",
        authors = {"Uxzylon"},
        dependencies = {@Dependency(id = "limboapi")}
)
public class LimboAutoServer {
    private final ProxyServer proxy;
    private final PluginLogger logger;
    private final Configuration config;

    private ServerManager serverManager;
    private LimboManager limboManager;

    @Inject
    public LimboAutoServer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        // DON'T ACCESS VELOCITY API HERE
        this.proxy = proxy;
        this.config = new Configuration(dataDirectory);
        this.logger = new PluginLogger(this, logger);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Loading configuration...");
        try {
            config.reloadConfig();
        } catch (Exception e) {
            logger.error("Failed to load config! Stopping plugin initialization.");
            logger.error(e.getMessage());
            throw new RuntimeException("Failed to load config! Stopping plugin initialization.", e);
        }
        logger.info("Configuration Loaded");

        serverManager = new ServerManager(this);
        limboManager = new LimboManager(this);
        limboManager.rebuild();

        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder("limboautoserver").aliases("las").plugin(this).build();
        commandManager.register(commandMeta, new LimboAutoServerCommand(this));

        proxy.getScheduler()
                .buildTask(this, () -> {
                    logger.trace("Maintenance task running.");
                    serverManager.validateServers(proxy.getAllServers());
                })
                .repeat(config.getMaintenanceInterval(), TimeUnit.MINUTES)
                .schedule();

        logger.info("Successfully enabled LimboAutoServer");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (limboManager != null) {
            limboManager.dispose();
        }
        logger.info("Successfully disabled LimboAutoServer");
    }

    /**
     * Fired by LimboAPI during the login process, before the player is routed to a
     * backend. If the target backend is offline we register an on-join callback
     * that drops the player into the limbo world instead of letting Velocity time
     * out while the server boots.
     */
    @Subscribe
    public EventTask onLoginLimboRegister(LoginLimboRegisterEvent event) {
        Player player = event.getPlayer();
        Optional<RegisteredServer> targetOpt = resolveTargetServer(player);
        if (targetOpt.isEmpty()) {
            return null; // Not a managed server; let Velocity handle it normally.
        }
        RegisteredServer target = targetOpt.get();

        return EventTask.resumeWhenComplete(
                serverManager.isServerResponsive(target).thenAccept(responsive -> {
                    if (responsive) {
                        // Server is up; a player is incoming, so cancel any pending shutdown.
                        serverManager.cancelShutdownServer(target);
                    } else {
                        logger.info("Server {}{}{} is offline; {} will wait in limbo.",
                                AnsiColors.RED, target.getServerInfo().getName(), AnsiColors.RESET, player.getUsername());
                        event.addOnJoinCallback(() -> limboManager.spawnIntoLimbo(player, target));
                    }
                }));
    }

    /**
     * A player is heading to a server (initial join, server switch, or the
     * transfer out of limbo). Cancel any pending auto-shutdown for that server so
     * it is not stopped out from under a returning player.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }
        RegisteredServer target = event.getResult().getServer().orElse(event.getOriginalServer());
        serverManager.cancelShutdownServer(target);
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        RegisteredServer previousServer = event.getPreviousServer();
        if (previousServer != null && previousServer.getPlayersConnected().isEmpty()) {
            serverManager.scheduleShutdownServer(previousServer);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        Optional<ServerConnection> serverConnection = player.getCurrentServer();
        if (serverConnection.isEmpty()) {
            return;
        }
        RegisteredServer server = serverConnection.get().getServer();
        // Count manually to avoid a race where the disconnecting player is still listed.
        int others = 0;
        for (Player p : server.getPlayersConnected()) {
            if (!p.getUniqueId().equals(player.getUniqueId())) {
                others++;
            }
        }
        if (others <= 0) {
            serverManager.scheduleShutdownServer(server);
        }
    }

    @Subscribe
    public void onPlayerKicked(KickedFromServerEvent event) {
        RegisteredServer server = event.getServer();
        if (server.getPlayersConnected().isEmpty()) {
            serverManager.scheduleShutdownServer(server);
        }
    }

    /**
     * Resolves the backend server a freshly-connecting player would be sent to:
     * a forced host mapping for their virtual host if present, otherwise the first
     * entry of Velocity's try list. Only returns a server this plugin manages
     * (i.e. one with a configured start command).
     */
    private Optional<RegisteredServer> resolveTargetServer(Player player) {
        List<String> candidates = null;

        String virtualHost = player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null);
        if (virtualHost != null) {
            Map<String, List<String>> forcedHosts = proxy.getConfiguration().getForcedHosts();
            candidates = forcedHosts.get(virtualHost.toLowerCase());
        }
        if (candidates == null || candidates.isEmpty()) {
            candidates = proxy.getConfiguration().getAttemptConnectionOrder();
        }

        for (String name : candidates) {
            Optional<RegisteredServer> server = proxy.getServer(name);
            if (server.isPresent() && config.isManaged(server.get())) {
                return server;
            }
        }
        return Optional.empty();
    }

    public void reload() {
        config.reloadConfig();
        limboManager.rebuild();
    }

    public PluginLogger getLogger() {
        return logger;
    }

    public ProxyServer getProxy() {
        return proxy;
    }

    public Configuration getConfig() {
        return config;
    }

    public ServerManager getServerManager() {
        return serverManager;
    }
}
