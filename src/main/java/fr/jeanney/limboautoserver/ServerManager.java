package fr.jeanney.limboautoserver;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of backend servers: starting, stopping, status pinging
 * and scheduled auto-shutdown.
 *
 * <p>Adapted from AutoServer (MIT, Artificial-720). Trimmed to local (process)
 * start/stop only and reworked so that concurrent callers waiting for the same
 * server to boot share a single startup future (replacing AutoServer's
 * player-queue mechanism, which is no longer needed now that players wait in a
 * Limbo world).</p>
 */
public class ServerManager {
    private final PluginLogger logger;
    private final LimboAutoServer plugin;
    private final Map<String, CompletableFuture<Void>> startupFutures = new ConcurrentHashMap<>();
    private final Map<String, ServerStatus> serverStatusCache = new ConcurrentHashMap<>();
    private final Map<String, ScheduledTask> shutdownScheduledTask = new ConcurrentHashMap<>();

    public ServerManager(LimboAutoServer plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Ensures the given server is started and responsive. If a startup is already
     * in progress for this server, the in-flight future is returned so that all
     * waiting players are released together.
     *
     * @return a future that completes once the server is responsive, or completes
     *         exceptionally if the server could not be started.
     */
    public CompletableFuture<Void> ensureServerStarted(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        CompletableFuture<Void> future = startupFutures.computeIfAbsent(serverName, name -> {
            logger.debug("Attempting to start server: {}", name);
            getServerStatus(server).setStatus(ServerStatus.Status.STARTING);

            CompletableFuture<Void> f = isServerOnline(server).thenCompose(isOnline -> {
                if (isOnline) {
                    return CompletableFuture.completedFuture(null);
                }
                return runStart(server)
                        .thenCompose(result -> waitForServerToBecomeResponsive(server))
                        .thenAccept(isResponsive -> {
                            if (!isResponsive) {
                                throw new CompletionException(new RuntimeException("Server started but is not responsive."));
                            }
                        });
            });

            f.whenComplete((result, ex) -> {
                startupFutures.remove(name);
                if (ex != null) {
                    logger.error("Failed to start server {}: {}", name, ex.getMessage());
                    if (!getServerStatus(server).is(ServerStatus.Status.RUNNING)) {
                        getServerStatus(server).setStatus(ServerStatus.Status.STOPPED);
                    }
                }
            });
            return f;
        });
        return future;
    }

    /**
     * Stops a given server if it is currently running.
     */
    public CompletableFuture<String> stopServer(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        if (getServerStatus(server).isStopping()) {
            logger.debug("Server {} is already stopping", serverName);
            return CompletableFuture.completedFuture("Server is already stopping.");
        }

        getServerStatus(server).setStatus(ServerStatus.Status.STOPPING);
        logger.info("Attempting to stop server: {}", serverName);

        return isServerOnline(server)
                .thenCompose(isOnline -> {
                    if (!isOnline) {
                        return CompletableFuture.completedFuture("Server already stopped");
                    }
                    return runStop(server).thenCompose(result -> {
                        long shutdownDelay = plugin.getConfig().getShutdownDelay(server);
                        try {
                            logger.info("Sleeping for {} seconds before checking if server has stopped.", shutdownDelay);
                            Thread.sleep(shutdownDelay * 1000);
                        } catch (InterruptedException e) {
                            logger.warn("Stop delay sleep interrupted: {}", e.getMessage());
                            Thread.currentThread().interrupt();
                        }
                        return isServerOnline(server).thenApply(isOnline2 -> {
                            if (isOnline2) {
                                throw new CompletionException(new RuntimeException("Failed to stop server."));
                            }
                            return "Server stopped.";
                        });
                    });
                })
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.error("Failed to stop server: {}", ex.getMessage());
                        getServerStatus(server).setStatus(ServerStatus.Status.UNKNOWN);
                    } else {
                        getServerStatus(server).setStatus(ServerStatus.Status.STOPPED);
                    }
                });
    }

    /**
     * Checks if the specified server is online (full 5s ping).
     */
    public CompletableFuture<Boolean> isServerOnline(RegisteredServer server) {
        return pingServer(server, 5000);
    }

    /**
     * Quick responsiveness check, using the cached status and a short ping.
     */
    public CompletableFuture<Boolean> isServerResponsive(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        ServerStatus cachedStatus = getServerStatus(server);

        if (cachedStatus.is(ServerStatus.Status.STOPPED)) {
            logger.debug("Cache check for server '{}' is OFFLINE", serverName);
            return CompletableFuture.completedFuture(false);
        }
        if (!server.getPlayersConnected().isEmpty()) {
            logger.debug("Players detected on server '{}', assuming ONLINE", serverName);
            return CompletableFuture.completedFuture(true);
        }
        return pingServer(server, plugin.getConfig().getResponsivenessTimeout());
    }

    public ServerStatus getServerStatus(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        if (!serverStatusCache.containsKey(serverName)) {
            serverStatusCache.put(serverName, new ServerStatus());
            try {
                isServerOnline(server).get();
            } catch (InterruptedException | ExecutionException ignored) {
            }
        }
        return serverStatusCache.get(serverName);
    }

    /**
     * Whether a server is currently eligible to be auto-shut-down.
     *
     * <p>By default a server is eligible when it has no players. A server flagged
     * with {@code autoShutdownRequiresEmptyProxy} is only eligible when the whole
     * proxy is empty (no players on any backend), so a backbone/lobby server is
     * not stopped while players are off in other (e.g. cluster) backends.</p>
     */
    public boolean isEligibleForShutdown(RegisteredServer server) {
        if (plugin.getConfig().getAutoShutdownRequiresEmptyProxy(server)) {
            return plugin.getProxy().getPlayerCount() == 0;
        }
        return server.getPlayersConnected().isEmpty();
    }

    /**
     * Re-evaluates every managed server and schedules a shutdown for any that is
     * now eligible. Safe to call from any connection event; each call self-gates.
     */
    public void evaluateShutdowns() {
        for (RegisteredServer server : plugin.getProxy().getAllServers()) {
            scheduleShutdownServer(server);
        }
    }

    /**
     * Schedules the server for shutdown after the configured idle delay, provided
     * it is managed, online and currently {@link #isEligibleForShutdown eligible}.
     */
    public void scheduleShutdownServer(RegisteredServer server) {
        assert server != null;
        String serverName = server.getServerInfo().getName();

        long autoShutdownDelay = plugin.getConfig().getAutoShutdownDelay(server);
        if (autoShutdownDelay <= 0) {
            return;
        }
        if (getServerStatus(server).is(ServerStatus.Status.STOPPED)) {
            return;
        }
        if (!isEligibleForShutdown(server)) {
            return;
        }
        if (shutdownScheduledTask.containsKey(serverName)) {
            logger.trace("Server {} is already scheduled to stop", serverName);
            return;
        }

        logger.info("Scheduling shutdown of server {} in {} seconds", serverName, autoShutdownDelay);
        ScheduledTask scheduledTask = plugin.getProxy().getScheduler()
                .buildTask(plugin, () -> {
                    shutdownScheduledTask.remove(serverName);
                    if (!isEligibleForShutdown(server)) {
                        logger.debug("Skipping shutdown of {}: players present.", serverName);
                        return;
                    }
                    stopServer(server).whenComplete((result, ex) -> {
                        if (ex != null) {
                            logger.error("error: {}", ex.getMessage());
                        } else {
                            logger.info("Message: {}", result);
                        }
                    });
                })
                .delay(Duration.ofSeconds(autoShutdownDelay))
                .schedule();
        shutdownScheduledTask.put(serverName, scheduledTask);
    }

    public void cancelShutdownServer(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        ScheduledTask task = shutdownScheduledTask.remove(serverName);
        if (task != null) {
            logger.info("Cancelling auto shutdown: {}", serverName);
            task.cancel();
        }
    }

    public void validateServers(Collection<RegisteredServer> servers) {
        logger.trace("Validating Server status...");
        for (RegisteredServer server : servers) {
            if (!plugin.getConfig().isManaged(server)) {
                continue;
            }
            pingServer(server, 5000).thenApply(isOnline -> {
                if (isOnline) {
                    scheduleShutdownServer(server);
                }
                return null;
            });
        }
    }

    // ----- internals -----

    private CompletableFuture<String> runStart(RegisteredServer server) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<String> command = plugin.getConfig().getStartCommand(server);
            if (command.isEmpty()) {
                logger.error("Start command not found for {}", server.getServerInfo().getName());
                throw new CompletionException(new RuntimeException("Start command not found"));
            }
            Optional<String> path = plugin.getConfig().getPath(server);
            Optional<Boolean> preserveQuotes = plugin.getConfig().getPreserveQuotes(server);
            logger.info("Running start command for {} server. \"{}{}{}\"",
                    server.getServerInfo().getName(), AnsiColors.YELLOW, command.get(), AnsiColors.RESET);
            return runCommand(path.orElse(null), command.get(), preserveQuotes.orElse(null));
        });
    }

    private CompletableFuture<String> runStop(RegisteredServer server) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<String> command = plugin.getConfig().getStopCommand(server);
            if (command.isEmpty()) {
                logger.error("Stop command not found for {}", server.getServerInfo().getName());
                throw new CompletionException(new RuntimeException("Stop command not found"));
            }
            Optional<String> path = plugin.getConfig().getPath(server);
            Optional<Boolean> preserveQuotes = plugin.getConfig().getPreserveQuotes(server);
            logger.info("Running stop command for {} server. \"{}{}{}\"",
                    server.getServerInfo().getName(), AnsiColors.YELLOW, command.get(), AnsiColors.RESET);
            return runCommand(path.orElse(null), command.get(), preserveQuotes.orElse(null));
        });
    }

    private String runCommand(String path, String command, Boolean preserveQuotes) {
        CommandRunner.CommandResult commandResult = CommandRunner.runCommand(path, command, preserveQuotes);
        // Give the command a moment so we can catch immediate failures.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (commandResult.failedToStart()) {
            throw new CompletionException(new RuntimeException(commandResult.getErrorMessage()));
        }

        logger.debug("Command Result: {}", commandResult);
        if (commandResult.isTerminated()) {
            String out = commandResult.getProcessOutput();
            if (!out.isBlank()) {
                logger.info("The command exited quickly, which may indicate an error. Output: {}{}{}",
                        AnsiColors.YELLOW, out, AnsiColors.RESET);
            }
            if (commandResult.getExitCode() != 0) {
                logger.error("Process exited with a non-zero exit code: {}", commandResult.getExitCode());
                throw new CompletionException(new RuntimeException("Command failed to run."));
            }
        }
        return "Command ran successfully";
    }

    private CompletableFuture<Boolean> pingServer(RegisteredServer server, int pingTimeout) {
        String serverName = server.getServerInfo().getName();
        logger.debug("Pinging server {}...", serverName);
        return server.ping().orTimeout(pingTimeout, TimeUnit.MILLISECONDS).thenApply(serverPing -> {
            logger.debug("ping success {} is {}online{}", serverName, AnsiColors.GREEN, AnsiColors.RESET);
            if (!getServerStatus(server).isStopping()) {
                getServerStatus(server).setStatus(ServerStatus.Status.RUNNING);
            }
            return true;
        }).exceptionallyCompose(e -> {
            logger.debug("ping failed for {}: {}", serverName, e.getMessage());
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage() != null ? cause.getMessage() : "";
            if (msg.contains("A packet did not decode successfully")) {
                logger.debug("failed to decode packet, likely online, trying socket connect");
                return CompletableFuture.supplyAsync(() -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(server.getServerInfo().getAddress());
                        logger.warn("Socket connection to {} succeeded, treating as online.", serverName);
                        if (!getServerStatus(server).isStopping()) {
                            getServerStatus(server).setStatus(ServerStatus.Status.RUNNING);
                        }
                        return true;
                    } catch (IOException ioe) {
                        logger.warn("Socket connection to {} failed after ping error.", serverName);
                        if (!getServerStatus(server).isStarting()) {
                            getServerStatus(server).setStatus(ServerStatus.Status.STOPPED);
                        }
                        return false;
                    }
                });
            }

            logger.debug("ping failed {} is {}offline{}", serverName, AnsiColors.RED, AnsiColors.RESET);
            if (!getServerStatus(server).isStarting()) {
                getServerStatus(server).setStatus(ServerStatus.Status.STOPPED);
            }
            return CompletableFuture.completedFuture(false);
        });
    }

    private CompletableFuture<Boolean> waitForServerToBecomeResponsive(RegisteredServer server) {
        return CompletableFuture.supplyAsync(() -> {
            int retries = 60;
            int delayBetweenRetries = 5; // seconds
            long startupDelay = plugin.getConfig().getStartUpDelay(server);

            try {
                logger.info("Sleeping for {} seconds before checking if server has started.", startupDelay);
                Thread.sleep(startupDelay * 1000);
            } catch (InterruptedException e) {
                logger.warn("Ping delay sleep interrupted: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }

            while (retries > 0) {
                try {
                    if (pingServer(server, 5000).get()) {
                        logger.info("Server {} is {}online{}.", server.getServerInfo().getName(),
                                AnsiColors.GREEN, AnsiColors.RESET);
                        return true;
                    }
                    logger.debug("Failed to ping server {}. Retrying in {} seconds.",
                            server.getServerInfo().getName(), delayBetweenRetries);
                } catch (ExecutionException | InterruptedException e) {
                    logger.debug("Failed to ping server {}: {}. Retrying in {} seconds.",
                            server.getServerInfo().getName(), e.getMessage(), delayBetweenRetries);
                }

                retries--;
                if (retries > 0) {
                    try {
                        Thread.sleep(delayBetweenRetries * 1000L);
                    } catch (InterruptedException e) {
                        logger.warn("Ping retry sleep interrupted: {}", e.getMessage());
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return false;
        });
    }
}
