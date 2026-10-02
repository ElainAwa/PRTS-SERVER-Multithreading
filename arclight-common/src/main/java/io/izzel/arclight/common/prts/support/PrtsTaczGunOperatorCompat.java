/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Re-syncs a gun mod's per-player state after a skin plugin pushed a same-level respawn packet: the
 * client does not run its own respawn recovery for that shape, so a value the respawn swallowed would
 * leave the weapon unable to fire. Every call is reflective, because the mod is optional; a missing
 * or moved signature reports and returns instead of failing.
 */
public final class PrtsTaczGunOperatorCompat {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-TACZ");

    private static final long RESYNC_DELAY_MS = 200L;

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PRTS-TACZ-sync");
            thread.setDaemon(true);
            return thread;
        });

    private PrtsTaczGunOperatorCompat() {
    }

    /** Re-initialises the player's gun operator and schedules the full state re-send. */
    public static void resetAndResync(ServerPlayer player) {
        if (player == null || !player.isAlive()) {
            return;
        }
        String name = player.getName().getString();
        try {
            Class<?> operatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
            Object operator = operatorClass.getMethod("fromLivingEntity", LivingEntity.class)
                .invoke(null, player);
            operatorClass.getMethod("initialData").invoke(operator);
        } catch (Throwable thrown) {
            // A missing or moved mod signature is a normal state for an optional mod.
            LOGGER.debug("[PRTS-TACZ] operator reset skipped player={} cause={}", name, thrown.toString());
            return;
        }
        PrtsModSupportStats.count("tacz-gun-state-resyncs");
        SCHEDULER.schedule(() -> resyncLater(player), RESYNC_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void resyncLater(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (!player.isAlive() || player.connection == null) {
                return;
            }
            sendFullState(player);
        });
    }

    private static void sendFullState(ServerPlayer player) {
        try {
            Class<?> dataClass = Class.forName("com.tacz.guns.entity.sync.core.SyncedEntityData");
            Object data = dataClass.getMethod("instance").invoke(null);
            Object holder = dataClass.getMethod("getDataHolder", Entity.class).invoke(data, player);
            if (holder == null) {
                return;
            }
            Object gathered = holder.getClass().getMethod("gatherAll").invoke(holder);
            if (!(gathered instanceof List<?> entries) || entries.isEmpty()) {
                return;
            }
            Class<?> messageClass =
                Class.forName("com.tacz.guns.network.message.ServerMessageUpdateEntityData");
            Object payload = messageClass.getConstructor(int.class, List.class)
                .newInstance(player.getId(), entries);
            player.connection.send(new ClientboundCustomPayloadPacket((CustomPacketPayload) payload));
        } catch (Throwable thrown) {
            LOGGER.debug("[PRTS-TACZ] state re-send skipped player={} cause={}",
                player.getName().getString(), thrown.toString());
        }
    }

    /**
     * @return a one line readout of the four synced cooldown values, or an explanation when the mod is
     *     not readable
     */
    public static String snapshot(LivingEntity player) {
        try {
            return "shootCD=" + cooldown(player, "SHOOT_COOL_DOWN_KEY")
                + " meleeCD=" + cooldown(player, "MELEE_COOL_DOWN_KEY")
                + " drawCD=" + cooldown(player, "DRAW_COOL_DOWN_KEY")
                + " sprint=" + cooldown(player, "SPRINT_TIME_KEY");
        } catch (Throwable thrown) {
            return "snapshot-unavailable: " + thrown;
        }
    }

    private static long cooldown(LivingEntity player, String keyField) throws Exception {
        Class<?> keys = Class.forName("com.tacz.guns.entity.sync.ModSyncedEntityData");
        Field field = keys.getField(keyField);
        Object key = field.get(null);
        Object raw = key.getClass().getMethod("getValue", Entity.class).invoke(key, player);
        return raw instanceof Number number ? number.longValue() : -1L;
    }
}
