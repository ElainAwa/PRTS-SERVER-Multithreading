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

/**
 * The {@code /prts} command: {@code reload} re-reads {@code prts-config/**} without restarting the
 * process, {@code status} prints the configuration directory, the category switches that are in
 * effect, and every problem the last read found.
 *
 * <p>The readout stays inside what this layer owns: the configuration directory, the resolved
 * category switches and the state of their files. Observation counters belong to the scheduling
 * kernel, so they are deliberately not reported here and no second source of numbers appears.</p>
 *
 * <p>The dispatcher side requires permission level 2, which the console, the remote console and
 * operators hold. The Bukkit view additionally declares and enforces the {@code prts.command}
 * permission, so the command map side answers to a permission a plugin can grant.</p>
 *
 * <p>A server command has to exist in both command worlds of this platform. The console and the
 * remote console parse the dispatcher the server owns, while players and plugins resolve commands
 * through the Bukkit command map; registering into only one of them leaves the command unreachable
 * from the other. {@link #register(CommandDispatcher)} and {@link #registerInCommandMap()} are
 * therefore both called by the platform registration hook.</p>
 *
 * <p>Lives in the configuration package on purpose: the mixin configuration declares the fixes
 * package as a mixin package, and Mixin refuses to load a class from such a package directly.</p>
 */
public final class PrtsCommand {

    private static final String NAME = "prts";

    /** Permission a plugin can grant; the console and the remote console always hold it. */
    private static final String PERMISSION = "prts.command";

    /** Vanilla permission level of the dispatcher side; the console and operators hold it. */
    private static final int PERMISSION_LEVEL = 2;

    private static final String USAGE = "usage: /prts reload | /prts status";

    private PrtsCommand() {
    }

    /**
     * Adds the command to a brigadier dispatcher.
     *
     * @param dispatcher dispatcher of a {@code Commands} instance; every rebuild has to be fed again
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(node());
    }

    /**
     * Adds the command to the Bukkit command map, when a server already exists.
     *
     * <p>Called before the server exists this does nothing: the platform hook fires again for every
     * later rebuild of the command dispatcher, and the command map is rebuilt from it.</p>
     */
    public static void registerInCommandMap() {
        try {
            if (Bukkit.getServer() instanceof CraftServer server) {
                server.getCommandMap().register("arclight", new BukkitView());
            }
        } catch (Throwable ignored) {
            // an unavailable command map must not break server start; the dispatcher keeps working
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal(NAME)
            .requires(source -> source.hasPermission(PERMISSION_LEVEL))
            .executes(context -> reply(context.getSource(), List.of(USAGE)))
            .then(Commands.literal("reload")
                .executes(context -> reply(context.getSource(), reload())))
            .then(Commands.literal("status")
                .executes(context -> reply(context.getSource(), status())));
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
        lines.addAll(problems());
        return lines;
    }

    private static List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("[PRTS] config directory: " + PrtsConfigManager.directory().toAbsolutePath());
        lines.add(switches());
        lines.addAll(features());
        lines.addAll(problems());
        return lines;
    }

    /**
     * Renders every category switch in a stable order, marking the ones a system property overrides.
     *
     * @return the single line that both command worlds print
     */
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

    /**
     * Renders the per-feature switches of every category that declares one.
     *
     * <p>Each line carries the effective value, so an operator sees what the running process
     * actually resolved instead of what the file claims.</p>
     *
     * @return one line per category with declared features, in category order
     */
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

    /**
     * Renders the problems of the last configuration read, one line per category.
     *
     * @return the problem lines, empty when the last read was clean
     */
    private static List<String> problems() {
        List<String> lines = new ArrayList<>();
        PrtsConfigManager.problems().forEach((category, problem) ->
            lines.add("[PRTS] config problem: " + category + ": " + problem));
        return lines;
    }

    /**
     * Bukkit-side view of the same command, so the command map accepts the label and checks the
     * permission a plugin can grant.
     */
    private static final class BukkitView extends Command {

        private BukkitView() {
            super(NAME);
            setDescription("PRTS server administration");
            setUsage(USAGE);
            setPermission(PERMISSION);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
                send(sender, reload());
            } else if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
                send(sender, status());
            } else {
                send(sender, List.of(USAGE));
            }
            return true;
        }

        private static void send(CommandSender sender, List<String> lines) {
            for (String line : lines) {
                sender.sendMessage(line);
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? List.of("reload", "status") : List.of();
        }
    }
}
