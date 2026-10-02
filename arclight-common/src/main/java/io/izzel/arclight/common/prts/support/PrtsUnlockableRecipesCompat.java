/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Method;

/**
 * Sends the recipe refresh of the optional recipe mod to the player who logged in, and to nobody else:
 * that mod refreshes every online player, and each client rebuilds its whole recipe and search index,
 * which stalls its render thread for seconds. The payload is built through the mod's own factory by
 * name, and when the factory cannot be found the caller leaves the mod's broadcast alone.
 */
public final class PrtsUnlockableRecipesCompat {

    private static final String CLASS_PAYLOAD = "com.zanghero.unlockablerecipes.network.RecipeBookPayload";
    private static final String METHOD_FOR_SERVER = "forServer";

    private static volatile boolean resolved;
    private static volatile Method factory;

    private PrtsUnlockableRecipesCompat() {
    }

    /**
     * @return true when both packets were sent to the joiner; false leaves the mod its own path
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
            // A moved signature is a normal state for an optional mod: the caller keeps the mod's behaviour.
            return false;
        }
    }

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
        resolved = true;
    }

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
