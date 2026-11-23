/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.Entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The identity and declared segments of one owner row while its body runs on this thread. A body
 * binds one context, the program entry declares a segment through it, and the binding ends before
 * the host frame continues. */
final class EntityUpdateProgramContext {

    record Identity(String worldId, int entityId, long tick, long generation) {
    }

    private static final ThreadLocal<EntityUpdateProgramContext> CURRENT = new ThreadLocal<>();

    private final Identity identity;
    private final EntityUpdateProgramContext previous;
    private final Set<EntityUpdateSegmentPolicy.Segment> entered =
        EnumSet.noneOf(EntityUpdateSegmentPolicy.Segment.class);
    private final Map<EntityUpdateSegmentPolicy.Segment, Integer> executed =
        new EnumMap<>(EntityUpdateSegmentPolicy.Segment.class);

    private EntityUpdateProgramContext(Identity identity, EntityUpdateProgramContext previous) {
        this.identity = identity;
        this.previous = previous;
    }

    static void begin(Identity identity) {
        // A row body starting inside a read node is a write the wave never ordered: the fence
        // records it and throws before any segment of the body can run.
        io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeReadGuard.fence(
            io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeReadGuard.Refusal.WRITE);
        if (identity == null) {
            throw new IllegalArgumentException("owner program identity is required");
        }
        CURRENT.set(new EntityUpdateProgramContext(identity, CURRENT.get()));
    }

    static void end() {
        EntityUpdateProgramContext current = CURRENT.get();
        if (current == null) {
            return;
        }
        if (current.previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(current.previous);
        }
    }

    static EntityUpdateProgramContext current() {
        return CURRENT.get();
    }

    Identity identity() {
        return identity;
    }

    boolean matches(Entity entity) {
        if (entity == null) {
            return false;
        }
        String worldId = entity.level().dimension().location().toString();
        long generation = io.izzel.arclight.common.prts.kernel.domain.entity.EntityObjectGenerations
            .shared().generationOf(worldId, entity.getUUID().getMostSignificantBits(),
                entity.getUUID().getLeastSignificantBits());
        return identity.entityId() == entity.getId() && identity.worldId().equals(worldId)
            && identity.tick() == entity.level().getServer().getTickCount()
            && identity.generation() == generation;
    }

    boolean enterSegment(EntityUpdateSegmentPolicy.Segment segment) {
        return segment != null && entered.add(segment);
    }

    void leaveSegment(EntityUpdateSegmentPolicy.Segment segment) {
        entered.remove(segment);
    }

    void noteSegmentExecuted(EntityUpdateSegmentPolicy.Segment segment) {
        if (!entered.contains(segment)) {
            throw new IllegalStateException("segment was not entered: " + segment);
        }
        executed.merge(segment, 1, Integer::sum);
    }

    int executedCount(EntityUpdateSegmentPolicy.Segment segment) {
        return executed.getOrDefault(segment, 0);
    }

    Set<EntityUpdateSegmentPolicy.Segment> executedSegments() {
        return Set.copyOf(executed.keySet());
    }
}
