/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Method;

/**
 * Sends the two packets one login needs, to that login's player, and to nobody else.
 *
 * <p>The optional recipe mod refreshes the recipe state of <em>every</em> player online when one
 * player logs in. Each client reacts to the refresh by rebuilding its whole recipe and search
 * index, which stops that client's render thread for seconds, so a single login stalls every other
 * player on the server. Only the player who logged in needs the refresh; nothing about the state of
 * the others changed.</p>
 *
 * <p>What the mod sends is built here the same way the mod builds it: one recipe packet and one
 * payload carrying the lock state, in that order, per player. The payload is created through the
 * mod's own factory, by name, so nothing here becomes a compile time or class loading dependency
 * of the server; when the factory cannot be found the caller leaves the mod's own broadcast alone
 * instead of guessing.</p>
 */
public final class PrtsUnlockableRecipesCompat {

    private static final String CLASS_PAYLOAD = "com.zanghero.unlockablerecipes.network.RecipeBookPayload";
    private static final String METHOD_FOR_SERVER = "forServer";

    private static volatile boolean resolved;
    private static volatile Method factory;

    private PrtsUnlockableRecipesCompat() {
    }

    /**
     * Sends the recipe refresh of the mod to one player.
     *
     * @param server server the login happened on
     * @param player player who logged in
     * @param anchor instance of a class of the mod, used as the anchor for the first lookup
     * @return {@code true} when both packets were sent; {@code false} leaves the mod its own path
     */
    public static boolean syncToJoiner(MinecraftServer server, ServerPlayer player, Object anchor) {
        Method method = factory(anchor);
        if (method == null) {
            return false;
        }
        try {
            Object payload = method.invoke(null, server, false, false);
            if (!(payload instanceof CustomPacketPayload custom)) {
                return false;
            }
            player.connection.send(new ClientboundUpdateRecipesPacket(server.getRecipeManager().getRecipes()));
            PacketDistributor.sendToPlayer(player, custom);
            return true;
        } catch (Throwable ignored) {
            // A moved signature is a normal state for an optional mod: the caller then keeps the
            // behaviour of the mod rather than sending an incomplete refresh.
            return false;
        }
    }

    /**
     * Resolves the payload factory of the mod once per process.
     *
     * @param anchor instance of a class of the mod
     * @return the factory, or {@code null} when it cannot be read
     */
    private static Method factory(Object anchor) {
        if (!resolved) {
            resolve(anchor);
        }
        return factory;
    }

    private static synchronized void resolve(Object anchor) {
        if (resolved) {
            return;
        }
        try {
            Class<?> payload = load(CLASS_PAYLOAD, anchor);
            if (payload != null) {
                factory = payload.getMethod(METHOD_FOR_SERVER, MinecraftServer.class, boolean.class, boolean.class);
            }
        } catch (Throwable ignored) {
            factory = null;
        }
        // a lookup that failed is not repeated: the answer is "unknown" for this process
        resolved = true;
    }

    /**
     * Loads a mod class through the loader of a class that certainly comes from the same mod.
     *
     * @param name class to load
     * @param anchor instance of a class of the same mod
     * @return the class, or {@code null} when it cannot be loaded
     */
    private static Class<?> load(String name, Object anchor) {
        try {
            return Class.forName(name, false, anchor.getClass().getClassLoader());
        } catch (Throwable ignored) {
            try {
                return Class.forName(name, false, PrtsUnlockableRecipesCompat.class.getClassLoader());
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }
}
