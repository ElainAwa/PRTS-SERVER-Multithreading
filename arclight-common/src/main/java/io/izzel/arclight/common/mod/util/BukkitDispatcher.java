package io.izzel.arclight.common.mod.util;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.izzel.arclight.common.mod.server.ArclightServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.command.BukkitCommandWrapper;
import org.bukkit.craftbukkit.v.command.VanillaCommandWrapper;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BukkitDispatcher extends CommandDispatcher<CommandSourceStack> {

    /** Fallback prefix the wrapped commands appear under in the Bukkit command map. */
    private static final String FALLBACK_PREFIX = "neoforge";

    /**
     * Commands registered before a server instance exists. The dispatcher is built by the data pack
     * load, which runs earlier, so those wrappers cannot reach the command map at registration time.
     * They wait here until {@link #flushPending()} runs.
     */
    private static final Queue<VanillaCommandWrapper> PENDING = new ConcurrentLinkedQueue<>();

    private final Commands commands;

    /**
     * Whether registrations are wrapped into the Bukkit command map. The vanilla command pass runs
     * with this off: those commands already reach the map as {@code minecraft:*} names, and wrapping
     * them here would add a second entry for every one of them.
     */
    private boolean modPhase;

    public BukkitDispatcher(Commands commands) {
        this.commands = commands;
    }

    /**
     * Turns wrapping on for the registrations that follow the vanilla pass.
     *
     * @param modPhase whether later registrations belong to the mod phase
     */
    public void setModPhase(boolean modPhase) {
        this.modPhase = modPhase;
    }

    @Override
    public LiteralCommandNode<CommandSourceStack> register(LiteralArgumentBuilder<CommandSourceStack> command) {
        LiteralCommandNode<CommandSourceStack> node = command.build();
        if (modPhase && !(node.getCommand() instanceof BukkitCommandWrapper)) {
            VanillaCommandWrapper wrapper = new VanillaCommandWrapper(this.commands, node);
            Server server = Bukkit.getServer();
            if (server == null) {
                PENDING.add(wrapper);
            } else {
                ((CraftServer) server).getCommandMap().register(FALLBACK_PREFIX, wrapper);
            }
        }
        getRoot().addChild(node);
        return node;
    }

    /**
     * Registers the commands that were built before the server instance existed.
     *
     * <p>Called once the server is constructed and before plugins are enabled, so a plugin that
     * resolves one of these commands while it starts finds it. The queue is drained, which makes the
     * call idempotent.</p>
     */
    public static void flushPending() {
        Server server = Bukkit.getServer();
        if (!(server instanceof CraftServer craftServer)) {
            return;
        }
        int flushed = 0;
        VanillaCommandWrapper wrapper;
        while ((wrapper = PENDING.poll()) != null) {
            craftServer.getCommandMap().register(FALLBACK_PREFIX, wrapper);
            flushed++;
        }
        if (flushed > 0) {
            ArclightServer.LOGGER.info("forwarded {} deferred command(s) to the bukkit command map", flushed);
        }
    }
}
