package fr.jeanney.limboautoserver.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import fr.jeanney.limboautoserver.LimboAutoServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

public class ReloadCommand implements SubCommand {
    private final LimboAutoServer plugin;

    public ReloadCommand(LimboAutoServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSource source, String[] args) {
        plugin.getLogger().info("Reloading configuration...");
        try {
            plugin.reload();
            plugin.getLogger().info("Configuration reloaded.");
            source.sendMessage(Component.text("Configuration reloaded.").color(NamedTextColor.GREEN));
        } catch (Exception e) {
            plugin.getLogger().error("Failed to load config! Fix errors and try again: {}", e.getMessage());
            source.sendMessage(Component.text("Configuration failed to reload.").color(NamedTextColor.RED));
        }
    }

    @Override
    public boolean hasPermission(SimpleCommand.Invocation invocation) {
        return invocation.source().hasPermission("limboautoserver.command.reload");
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return List.of();
    }

    @Override
    public String help() {
        return "Reloads the config file and rebuilds the limbo world";
    }
}
