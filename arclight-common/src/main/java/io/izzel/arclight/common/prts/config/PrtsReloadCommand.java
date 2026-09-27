/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;

import java.util.List;

/**
 * The {@code /prts} command, which reloads {@code prts-config/**} without restarting the process.
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
public final class PrtsReloadCommand {

    private static final String NAME = "prts";

    private static final String USAGE = "/prts reload";

    private PrtsReloadCommand() {
    }

    /**
     * Adds {@code /prts reload} to a brigadier dispatcher.
     *
     * @param dispatcher dispatcher of a {@code Commands} instance; every rebuild has to be fed again
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(node());
    }

    /**
     * Adds {@code /prts reload} to the Bukkit command map, when a server already exists.
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
            .then(Commands.literal("reload").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal(reload()), false);
                return 1;
            }));
    }

    private static String reload() {
        PrtsConfigManager.reload();
        return "[PRTS] prts-config reloaded: " + PrtsConfigManager.snapshot();
    }

    /**
     * Bukkit-side view of the same command, so the command map accepts the label.
     */
    private static final class BukkitView extends Command {

        private BukkitView() {
            super(NAME);
            setDescription("PRTS server administration");
            setUsage(USAGE);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
                sender.sendMessage(reload());
            } else {
                sender.sendMessage("usage: " + USAGE);
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? List.of("reload") : List.of();
        }
    }
}
