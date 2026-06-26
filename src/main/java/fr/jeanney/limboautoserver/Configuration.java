package fr.jeanney.limboautoserver;

import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * TOML-backed configuration.
 *
 * <p>Adapted from AutoServer (MIT, Artificial-720), trimmed to the local-start
 * use case and extended with the {@code [limbo]} section.</p>
 */
public class Configuration {
    private static final long DEFAULT_START_UP_DELAY = 5L;
    private static final long DEFAULT_SHUTDOWN_DELAY = 5L;
    private static final long DEFAULT_AUTO_SHUTDOWN_DELAY = -1L;

    private final Path dataDirectory;
    private Toml config;

    public Configuration(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.config = new Toml();
    }

    /**
     * Reloads the config from disk.
     */
    public void reloadConfig() throws RuntimeException {
        config = loadConfig(dataDirectory);
    }

    // ----- messages -----

    public Optional<String> getMessage(String messageType) {
        String prefix = config.getString("messages.prefix", "");
        String message = config.getString("messages." + messageType);
        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(prefix + message);
    }

    // ----- per-server settings -----

    public Optional<String> getPath(RegisteredServer server) {
        return Optional.ofNullable(config.getString(serverKey(server, "workingDirectory")));
    }

    public Optional<Boolean> getPreserveQuotes(RegisteredServer server) {
        return Optional.ofNullable(config.getBoolean(serverKey(server, "preserveQuotes")));
    }

    public Optional<String> getStartCommand(RegisteredServer server) {
        return Optional.ofNullable(config.getString(serverKey(server, "start")));
    }

    public Optional<String> getStopCommand(RegisteredServer server) {
        return Optional.ofNullable(config.getString(serverKey(server, "stop")));
    }

    /**
     * A server is "managed" by this plugin when a start command is configured for it.
     */
    public boolean isManaged(RegisteredServer server) {
        return getStartCommand(server).isPresent();
    }

    public long getStartUpDelay(RegisteredServer server) {
        return config.getLong(serverKey(server, "startupDelay"), DEFAULT_START_UP_DELAY);
    }

    public long getShutdownDelay(RegisteredServer server) {
        return config.getLong(serverKey(server, "shutdownDelay"), DEFAULT_SHUTDOWN_DELAY);
    }

    public long getAutoShutdownDelay(RegisteredServer server) {
        return config.getLong(serverKey(server, "autoShutdownDelay"), DEFAULT_AUTO_SHUTDOWN_DELAY);
    }

    // ----- global settings -----

    public String getLogLevel() {
        return config.getString("logging.level", "INFO").toUpperCase();
    }

    public long getMaintenanceInterval() {
        return config.getLong("maintenanceInterval", 5L);
    }

    public long getStartRateLimit() {
        return config.getLong("startRateLimit", 6L);
    }

    // ----- limbo settings -----

    public String getLimboDimension() {
        return config.getString("limbo.dimension", "OVERWORLD").toUpperCase();
    }

    public String getLimboGameMode() {
        return config.getString("limbo.gameMode", "ADVENTURE").toUpperCase();
    }

    public long getLimboWorldTime() {
        return config.getLong("limbo.worldTime", 1000L);
    }

    public double getLimboPosX() {
        return config.getDouble("limbo.posX", 0.0);
    }

    public double getLimboPosY() {
        return config.getDouble("limbo.posY", 100.0);
    }

    public double getLimboPosZ() {
        return config.getDouble("limbo.posZ", 0.0);
    }

    public double getLimboYaw() {
        return config.getDouble("limbo.yaw", 0.0);
    }

    public double getLimboPitch() {
        return config.getDouble("limbo.pitch", 0.0);
    }

    // ----- helpers -----

    private String serverKey(RegisteredServer server, String key) {
        return "servers." + server.getServerInfo().getName() + "." + key;
    }

    private Toml loadConfig(Path path) throws RuntimeException {
        File configFile = new File(path.toFile(), "config.toml");
        try {
            if (!configFile.exists()) {
                if (!configFile.getParentFile().exists() && !configFile.getParentFile().mkdirs()) {
                    throw new IOException("Failed to create parent directories for config file.");
                }
                InputStream input = getClass().getResourceAsStream("/" + configFile.getName());
                if (input != null) {
                    Files.copy(input, configFile.toPath());
                } else if (!configFile.createNewFile()) {
                    throw new IOException("Failed to create a new config file.");
                }
            }
            return new Toml().read(configFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
