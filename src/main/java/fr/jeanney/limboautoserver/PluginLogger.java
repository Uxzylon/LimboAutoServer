package fr.jeanney.limboautoserver;

import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * Thin wrapper around the SLF4J logger that filters messages based on the
 * configured logging level.
 */
public class PluginLogger {
    private final Logger logger;
    private final LimboAutoServer plugin;

    public PluginLogger(LimboAutoServer plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void trace(String msg, Object... args) {
        log(Level.TRACE, msg, args);
    }

    public void debug(String msg, Object... args) {
        log(Level.DEBUG, msg, args);
    }

    public void info(String msg, Object... args) {
        log(Level.INFO, msg, args);
    }

    public void warn(String msg, Object... args) {
        log(Level.WARN, msg, args);
    }

    public void error(String msg, Object... args) {
        log(Level.ERROR, msg, args);
    }

    private void log(Level level, String message, Object... args) {
        if (!shouldLog(level)) {
            return;
        }
        switch (level) {
            case TRACE, DEBUG, INFO -> logger.info(message, args);
            case WARN -> logger.warn(message, args);
            case ERROR -> logger.error(message, args);
        }
    }

    private boolean shouldLog(Level level) {
        Level configured;
        try {
            configured = Level.valueOf(plugin.getConfig().getLogLevel());
        } catch (IllegalArgumentException e) {
            configured = Level.INFO;
        }
        return level.toInt() >= configured.toInt();
    }
}
