/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.izzel.arclight.common.prts.PrtsSwitches;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import io.izzel.arclight.common.prts.kernel.observe.KernelSelfCheck;
import io.izzel.arclight.common.prts.kernel.observe.KernelStatusLines;
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
 * effect, every problem the last read found and a compact kernel section, and {@code kernel}
 * exports the kernel readout field by field.
 *
 * <p>The kernel export is the observation outlet of the new kernel scaffolding. The field names it
 * prints are observation requests rather than an approved counter table, so the export marks itself
 * as such and publishes every field even when its value is zero. {@code kernel selftest} runs the
 * decision matrix of the four pieces on scratch objects, which leaves the live counters alone.</p>
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

    private static final String USAGE =
        "usage: /prts reload | /prts status | /prts kernel [selftest]";

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
                .executes(context -> reply(context.getSource(), status())))
            .then(Commands.literal("kernel")
                .executes(context -> reply(context.getSource(), kernel()))
                .then(Commands.literal("selftest")
                    .executes(context -> reply(context.getSource(), kernelSelftest()))));
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
        lines.addAll(KernelStatusLines.status(KernelModule.instance()));
        lines.addAll(features());
        lines.addAll(numbers());
        lines.addAll(upgrades());
        lines.addAll(problems());
        return lines;
    }

    /**
     * Renders the full kernel readout, one {@code name=value} field per line.
     *
     * @return the export lines, prefixed so the command world shows them as one block
     */
    private static List<String> kernel() {
        List<String> lines = new ArrayList<>();
        lines.add("[PRTS] kernel export (observation requests; zero values are published too)");
        for (String line : KernelReadings.export(KernelModule.instance())) {
            lines.add("[PRTS] kernel " + line);
        }
        return lines;
    }

    /**
     * Runs the decision matrix of the four kernel pieces on scratch objects.
     *
     * @return the result lines; the live counters are not touched
     */
    private static List<String> kernelSelftest() {
        List<String> lines = new ArrayList<>();
        for (String line : KernelSelfCheck.run()) {
            lines.add("[PRTS] kernel " + line);
        }
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
     * Renders the whole-number settings of every category that declares one.
     *
     * <p>Shown next to the switches for the same reason: an operator sees the value the running
     * process resolved, so a typo that was clamped or ignored is visible here instead of only in
     * the problem lines.</p>
     *
     * @return one line per category with declared settings, in category order
     */
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

    /**
     * Renders the files this process brought up to the current configuration layout at start.
     *
     * <p>An upgrade rewrites a file an operator owns, so the file and what changed in it are shown
     * here as well as in the start log. The line stays for the lifetime of the process: it is a
     * statement about what happened to the files on disk, not about the values in effect.</p>
     *
     * @return the upgrade lines, empty when every file already carried the current layout
     */
    private static List<String> upgrades() {
        List<String> lines = new ArrayList<>();
        PrtsConfigManager.upgrades().forEach((category, upgrade) ->
            lines.add("[PRTS] config upgraded: " + upgrade));
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
            } else if (args.length == 1 && "kernel".equalsIgnoreCase(args[0])) {
                send(sender, kernel());
            } else if (args.length == 2 && "kernel".equalsIgnoreCase(args[0])
                && "selftest".equalsIgnoreCase(args[1])) {
                send(sender, kernelSelftest());
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
            if (args.length == 1) {
                return List.of("reload", "status", "kernel");
            }
            if (args.length == 2 && "kernel".equalsIgnoreCase(args[0])) {
                return List.of("selftest");
            }
            return List.of();
        }
    }
}
