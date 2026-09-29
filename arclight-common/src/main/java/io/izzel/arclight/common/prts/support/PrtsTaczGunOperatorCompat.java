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
 * Runtime bridge that re-syncs a gun mod's per-player state after a respawn packet.
 *
 * <p>A skin plugin refreshes a player by pushing a same-level, keep-data respawn packet. The client
 * no longer runs its own respawn recovery for that shape, while the gun mod only re-sends a value it
 * considers changed. A value the respawn swallows stays stale on the client, and the weapon stops
 * firing although reloading still works. Re-initialising the operator and then sending the full
 * state once more - after the client had time to finish the respawn - clears that state.</p>
 *
 * <p>Every call is reflective on purpose: the mod is optional, so nothing here may become a compile
 * time or class-loading dependency of the server. When the mod is absent, or one of its signatures
 * moved, each entry point reports and returns instead of failing. The thread below is a daemon, so
 * it never keeps the process alive, and it only exists once a player actually respawns.</p>
 */
public final class PrtsTaczGunOperatorCompat {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-TACZ");

    /** Wall-clock delay given to the client to finish handling the respawn before the re-send. */
    private static final long RESYNC_DELAY_MS = 200L;

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PRTS-TACZ-sync");
            thread.setDaemon(true);
            return thread;
        });

    private PrtsTaczGunOperatorCompat() {
    }

    /**
     * Re-initialises the player's gun operator and schedules the full state re-send.
     *
     * @param player player the respawn packet was pushed to
     */
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
        SCHEDULER.schedule(() -> resyncLater(player), RESYNC_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Marshals the delayed re-send back onto the server thread and performs it.
     *
     * @param player player whose state is re-sent
     */
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

    /**
     * Sends the player the full synced state the mod would otherwise only send on a change.
     *
     * @param player player to send to
     */
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
     * Reads the four synced cooldown values the mod keeps for a living entity.
     *
     * @param player player to read
     * @return a one line readout, or an explanation when the mod is not readable
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
