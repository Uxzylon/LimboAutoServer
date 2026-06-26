package fr.jeanney.limboautoserver.commands;

import com.velocitypowered.api.command.SimpleCommand;
import fr.jeanney.limboautoserver.LimboAutoServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LimboAutoServerCommand implements SimpleCommand {
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();

    public LimboAutoServerCommand(LimboAutoServer plugin) {
        subCommands.put("reload", new ReloadCommand(plugin));
        subCommands.put("status", new StatusCommand(plugin));
        subCommands.put("start", new StartCommand(plugin));
        subCommands.put("stop", new StopCommand(plugin));
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendHelp(invocation);
            return;
        }

        String command = args[0].toLowerCase();
        SubCommand subCommand = subCommands.get(command);
        if (subCommand == null) {
            sendHelp(invocation);
            return;
        }
        if (!subCommand.hasPermission(invocation)) {
            invocation.source().sendMessage(Component.text("You don't have permission to do that.")
                    .color(NamedTextColor.RED));
            return;
        }
        subCommand.execute(invocation.source(), args);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String part = args.length == 1 ? args[0].toLowerCase() : "";
            List<String> result = new ArrayList<>();
            for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
                if (entry.getKey().startsWith(part) && entry.getValue().hasPermission(invocation)) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }

        SubCommand subCommand = subCommands.get(args[0].toLowerCase());
        if (subCommand != null && subCommand.hasPermission(invocation)) {
            return subCommand.suggest(invocation);
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return true;
        }
        SubCommand subCommand = subCommands.get(args[0].toLowerCase());
        return subCommand == null || subCommand.hasPermission(invocation);
    }

    private void sendHelp(Invocation invocation) {
        invocation.source().sendMessage(Component.text("LimboAutoServer commands:")
                .color(NamedTextColor.GOLD));
        subCommands.forEach((name, sub) -> {
            if (sub.hasPermission(invocation)) {
                invocation.source().sendMessage(Component.text("/las " + name + " - " + sub.help())
                        .color(NamedTextColor.GRAY));
            }
        });
    }
}
