/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.izzel.arclight.common.prts.PrtsSwitches;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code /prts} command: {@code reload} re-reads the configuration, {@code status} prints the
 * directory, the switches in effect, the problems of the last read and the status lines of every
 * registered extension. Both command worlds are served, because each reaches only one of them.
 */
public final class PrtsCommand {

    private static final String NAME = "prts";

    private static final String PERMISSION = "prts.command";

    private static final int PERMISSION_LEVEL = 2;

    private static final Map<String, PrtsCommandExtension> EXTENSIONS = new ConcurrentHashMap<>();

    private PrtsCommand() {
    }

    /** Registers a subtree of another layer; a later registration under the same name replaces it. */
    public static void registerExtension(PrtsCommandExtension extension) {
        if (extension != null && extension.name() != null && !extension.name().isBlank()) {
            EXTENSIONS.put(extension.name(), extension);
        }
    }

    /** Adds the command to a brigadier dispatcher; every rebuild has to be fed again. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(node());
    }

    /**
     * Adds the command to the Bukkit command map; before the server exists this does nothing, and the
     * platform hook fires again on every later rebuild of the dispatcher.
     */
    public static void registerInCommandMap() {
        try {
            if (Bukkit.getServer() instanceof CraftServer server) {
                server.getCommandMap().register("arclight", new BukkitView());
            }
        } catch (Throwable ignored) {
                // An unavailable command map must not break server start; the dispatcher keeps working.
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(NAME)
            .requires(source -> source.hasPermission(PERMISSION_LEVEL))
            .executes(context -> reply(context.getSource(), List.of(usage())))
            .then(Commands.literal("reload")
                .executes(context -> reply(context.getSource(), reload())))
            .then(Commands.literal("status")
                .executes(context -> reply(context.getSource(), status())));
        for (PrtsCommandExtension extension : extensions()) {
            node.then(extensionNode(extension));
        }
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> extensionNode(
        PrtsCommandExtension extension) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(extension.name())
            .executes(context -> reply(context.getSource(), extension.run(List.of())));
        for (String subcommand : extension.subcommands()) {
            node.then(Commands.literal(subcommand).executes(context ->
                reply(context.getSource(), extension.run(List.of(subcommand)))));
        }
        return node;
    }

    private static int reply(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static List<String> reload() {
        PrtsConfigManager.reload();
        List<String> lines = new ArrayList<>();
        lines.add("[PRTS] prts-config reloaded");
        lines.add(switches());
        lines.addAll(features());
        lines.addAll(numbers());
        lines.addAll(upgrades());
        lines.addAll(problems());
        return lines;
    }

    private static List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("[PRTS] config directory: " + PrtsConfigManager.directory().toAbsolutePath());
        lines.add(switches());
        for (PrtsCommandExtension extension : extensions()) {
            lines.addAll(extension.statusLines());
        }
        lines.addAll(features());
        lines.addAll(numbers());
        lines.addAll(upgrades());
        lines.addAll(problems());
        return lines;
    }

    private static String switches() {
        StringBuilder builder = new StringBuilder("[PRTS] category switches:");
        for (String category : new TreeSet<>(PrtsConfigManager.entries().keySet())) {
            builder.append(' ').append(category).append('=').append(PrtsSwitches.enabled(category));
            String override = PrtsSwitches.systemOverride(category);
            if (override != null) {
                builder.append(" (overridden by -Darclight.prts.").append(category).append('=')
                    .append(override).append(')');
            }
        }
        return builder.toString();
    }

    private static List<String> features() {
        List<String> lines = new ArrayList<>();
        for (String category : new TreeSet<>(PrtsConfigManager.entries().keySet())) {
            Map<String, Boolean> features = PrtsConfigManager.features(category);
            if (features.isEmpty()) {
                continue;
            }
            StringBuilder builder = new StringBuilder("[PRTS] features:");
            features.forEach((name, value) ->
                builder.append(' ').append(category).append('.').append(name).append('=').append(value));
            lines.add(builder.toString());
        }
        return lines;
    }

    private static List<String> numbers() {
        List<String> lines = new ArrayList<>();
        for (String category : new TreeSet<>(PrtsConfigManager.entries().keySet())) {
            Map<String, Integer> numbers = PrtsConfigManager.numbers(category);
            if (numbers.isEmpty()) {
                continue;
            }
            StringBuilder builder = new StringBuilder("[PRTS] settings:");
            numbers.forEach((name, value) ->
                builder.append(' ').append(category).append('.').append(name).append('=').append(value));
            lines.add(builder.toString());
        }
        return lines;
    }

    private static List<String> upgrades() {
        List<String> lines = new ArrayList<>();
        PrtsConfigManager.upgrades().forEach((category, upgrade) ->
            lines.add("[PRTS] config upgraded: " + upgrade));
        return lines;
    }

    private static List<String> problems() {
        List<String> lines = new ArrayList<>();
        PrtsConfigManager.problems().forEach((category, problem) ->
            lines.add("[PRTS] config problem: " + category + ": " + problem));
        return lines;
    }

    private static List<PrtsCommandExtension> extensions() {
        List<String> names = new ArrayList<>(EXTENSIONS.keySet());
        names.sort(String::compareTo);
        List<PrtsCommandExtension> ordered = new ArrayList<>(names.size());
        for (String name : names) {
            ordered.add(EXTENSIONS.get(name));
        }
        return ordered;
    }

    private static String usage() {
        StringBuilder builder = new StringBuilder("usage: /prts reload | /prts status");
        for (PrtsCommandExtension extension : extensions()) {
            builder.append(" | /prts ").append(extension.name());
            if (!extension.subcommands().isEmpty()) {
                builder.append(" [").append(String.join("|", extension.subcommands())).append(']');
            }
        }
        return builder.toString();
    }

    /** Bukkit-side view of the same command: the command map label plus the permission a plugin can grant. */
    private static final class BukkitView extends Command {

        private BukkitView() {
            super(NAME);
            setDescription("PRTS server administration");
            setPermission(PERMISSION);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            setUsage(usage());
            if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
                send(sender, reload());
            } else if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
                send(sender, status());
            } else if (args.length >= 1 && EXTENSIONS.containsKey(args[0])) {
                send(sender, EXTENSIONS.get(args[0]).run(rest(args)));
            } else {
                send(sender, List.of(usage()));
            }
            return true;
        }

        private static List<String> rest(String[] args) {
            List<String> rest = new ArrayList<>(args.length - 1);
            for (int index = 1; index < args.length; index++) {
                rest.add(args[index]);
            }
            return rest;
        }

        private static void send(CommandSender sender, List<String> lines) {
            for (String line : lines) {
                sender.sendMessage(line);
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                names.add("reload");
                names.add("status");
                for (PrtsCommandExtension extension : extensions()) {
                    names.add(extension.name());
                }
                return names;
            }
            if (args.length >= 2 && EXTENSIONS.containsKey(args[0])) {
                return EXTENSIONS.get(args[0]).complete(rest(args));
            }
            return List.of();
        }
    }
}
