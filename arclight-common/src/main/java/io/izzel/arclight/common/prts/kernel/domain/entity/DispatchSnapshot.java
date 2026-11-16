/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.support.EntityEligibility;
import io.izzel.arclight.common.prts.support.PrtsEntityCapability;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.minecraft.server.level.ServerLevel;

/** Takes the read-only entity view of one tick, on the tick thread: it copies plain numbers out of
 * the live entities and never keeps a reference to one. Every row it sees is also booked in the
 * per-tick identity ledger with its eligibility reason, so the rows the selection cap keeps out of
 * the frame stay countable instead of disappearing. */
public final class DispatchSnapshot {

    private static final ThreadLocal<EntityDueLedger> LAST_LEDGER = new ThreadLocal<>();

    private DispatchSnapshot() {
    }

    /** The ledger the last capture on this thread fed; the next capture of the tick replaces it. */
    public static EntityDueLedger lastLedger() {
        return LAST_LEDGER.get();
    }

    /** Builds one tick's frame from the due window the host froze in that same server tick: the
     * work source is the window's identity set, no world is listed and no loaded set is scanned;
     * the ledger is published under the same accessor as a legacy capture. */
    public static EntityDueWindow.Result captureDue(long tick, EntityDueCapture.Window window,
                                                    WorldEpochSource worldEpochs, int maxRows) {
        EntityDueWindow.Result result =
            EntityDueWindow.compile(tick, window.worlds(), worldEpochs, maxRows);
        LAST_LEDGER.set(result.ledger());
        return result;
    }

    public static List<EntityCandidateView> capture(WorldEpochSource worldEpochs) {
        return capture(worldEpochs, entityId -> false);
    }

    /** Captures the frame while leaving out entity ids another owner already claimed this tick. */
    public static List<EntityCandidateView> capture(WorldEpochSource worldEpochs,
                                                    IntPredicate skip) {
        List<EntityCandidateView> views = new ArrayList<>();
        // The ledger keeps every row the work source offers; it declares no capacity, so a row the
        // per-world selection cap refuses is still retained with its identity and reason.
        EntityDueLedger ledger = EntityDueLedger.open(-1L, Integer.MAX_VALUE);
        List<World> worlds;
        try {
            worlds = List.copyOf(Bukkit.getWorlds());
        } catch (Throwable ignored) {
            // A platform that cannot list its worlds contributes no candidates; the tick continues.
            EntityQueryReadings.noteEntityCount(0L);
            ledger.close();
            LAST_LEDGER.set(ledger);
            return views;
        }
        for (World world : worlds) {
            try {
                String worldId = WorldKey.id(world);
                views.add(capture(world, worldId, worldEpochs.epochOf(worldId), skip, ledger));
            } catch (Throwable ignored) {
                // A world that cannot be read contributes no candidates; the tick continues.
            }
        }
        long total = 0L;
        for (EntityCandidateView view : views) {
            total += view.count();
        }
        EntityQueryReadings.noteEntityCount(total);
        ledger.close();
        LAST_LEDGER.set(ledger);
        return views;
    }

    private static EntityCandidateView capture(World world, String worldId, long worldEpoch,
                                               IntPredicate skip, EntityDueLedger ledger) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(worldId, worldEpoch);
        for (Entity entity : world.getEntities()) {
            if (skip.test(entity.getEntityId())) {
                continue;
            }
            try {
                CraftEntity craft = (CraftEntity) entity;
                net.minecraft.world.entity.Entity handle = craft.getHandle();
                EntityRowIdentity identity = identityOf(worldId, worldEpoch, handle);
                if (entity instanceof Player) {
                    PrtsEntityCapability.playerRow();
                    ledger.observe(identity, EntityEligibility.Reason.PLAYER);
                    continue;
                }
                if (builder.full()) {
                    // The cap belongs to the frame, not to the world: the row is retained as a
                    // refusal and booked in the ledger, so a census can say which row it lost.
                    PrtsEntityCapability.noteCapDropped();
                    EntityEligibility.Reason refused = PrtsEntityCapability.classify(handle);
                    ledger.observe(identity, refused);
                    builder.refuse(identity, refused);
                    continue;
                }
                EntityEligibility.Reason reason = PrtsEntityCapability.census(handle);
                ledger.observe(identity, reason);
                Location location = entity.getLocation();
                builder.add(identity, reason, location.getBlockX() >> 4,
                    location.getBlockZ() >> 4, location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(), entity.getVelocity().getX(),
                    entity.getVelocity().getY(), entity.getVelocity().getZ(),
                    hostStep(handle));
            } catch (Throwable ignored) {
                // An entity that cannot be read contributes no candidate.
            }
        }
        return builder.build();
    }

    private static EntityRowIdentity identityOf(String worldId, long worldEpoch,
                                                net.minecraft.world.entity.Entity handle) {
        return new EntityRowIdentity(worldId, worldEpoch,
            handle.getUUID().getMostSignificantBits(), handle.getUUID().getLeastSignificantBits(),
            handle.getId(), handle.tickCount);
    }

    /** The kinematic step the host would take for one row; read before the row's tick runs. */
    public static long hostStep(net.minecraft.world.entity.Entity handle) {
        if (handle instanceof net.minecraft.world.entity.decoration.ArmorStand armorStand) {
            // The host lets an armour stand travel only while it has physics, which is exactly the
            // negation of the two flags below; a stand without it is dragged and never moved.
            if (armorStand.isMarker() || armorStand.isNoGravity()) {
                return EntityIntegrator.STEP_LIVING_NO_PHYSICS;
            }
            return EntityIntegrator.STEP_PLAIN;
        }
        if (handle instanceof net.minecraft.world.entity.item.ItemEntity item) {
            // An item in the air always moves and is dragged by a plain constant; an item resting
            // on a block takes its drag from that block, which is a world read this snapshot does
            // not make, and gravity is applied before the move, which this step does not state.
            if (item.isNoGravity() && !item.onGround()) {
                return EntityIntegrator.STEP_ITEM_AIR;
            }
            return EntityIntegrator.STEP_PLAIN;
        }
        return EntityIntegrator.STEP_PLAIN;
    }

    @FunctionalInterface
    public interface WorldEpochSource {

        long epochOf(String worldId);
    }

    /** The one key a world is named by: the snapshot stamps views with it, the write-back freezes it
     * and the write-right guard tracks the generation under it. */
    public static final class WorldKey {

        private WorldKey() {
        }

        public static String id(World world) {
            return world.getKey().toString();
        }

        public static String id(ServerLevel level) {
            return level.dimension().location().toString();
        }
    }
}
