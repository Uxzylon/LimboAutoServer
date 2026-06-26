package fr.jeanney.limboautoserver.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.jeanney.limboautoserver.LimboAutoServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Optional;

public class StopCommand implements SubCommand {
    private final LimboAutoServer plugin;

    public StopCommand(LimboAutoServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSource source, String[] args) {
        if (args.length != 2) {
            source.sendMessage(Component.text("Usage: /las stop <serverName>"));
            return;
        }

        String serverName = args[1];
        Optional<RegisteredServer> optionalServer = plugin.getProxy().getServer(serverName);
        if (optionalServer.isEmpty()) {
            source.sendMessage(Component.text("Server \"" + serverName + "\" not found.").color(NamedTextColor.RED));
            return;
        }
        RegisteredServer server = optionalServer.get();
        source.sendMessage(Component.text("Stopping server \"" + serverName + "\"... Please wait."));

        plugin.getServerManager().stopServer(server).whenComplete((result, ex) -> {
            if (ex != null) {
                source.sendMessage(Component.text("Failed to stop server: " + ex.getMessage())
                        .color(NamedTextColor.RED));
            } else {
                source.sendMessage(Component.text(result).color(NamedTextColor.GREEN));
            }
        });
    }

    @Override
    public boolean hasPermission(SimpleCommand.Invocation invocation) {
        return invocation.source().hasPermission("limboautoserver.command.stop");
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 2) {
            String part = args[1].toLowerCase();
            return plugin.getProxy().getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .filter(name -> name.toLowerCase().startsWith(part)).toList();
        }
        return List.of();
    }

    @Override
    public String help() {
        return "Run the stop sequence for a server";
    }
}
