/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.decision.RegistrationEpoch;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueCapture;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueWindow;
import io.izzel.arclight.common.prts.kernel.random.SharedStream;
import io.izzel.arclight.common.prts.kernel.random.SharedStreamTable;
import io.izzel.arclight.common.prts.kernel.random.StreamKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Captures one due window as the fixed input of a reference run from live sources only. Reversible
 * state comes from the values the tick thread froze in each row plus the body rotation read from
 * that live entity; a source that cannot be supplied refuses the capture by name. */
public final class ReferenceCapture {

    /** The stable business name of the stream a reference input carries. */
    private static final String REFERENCE_BUSINESS = "reference";

    /** One capture attempt: an input or a named unbound reason. */
    public record Result(ReferenceInput input, String unboundReason) {

        public boolean bound() {
            return input != null && (unboundReason == null || unboundReason.isEmpty());
        }
    }

    /**
     * The live sources one capture reads: seed, registration, owners, wait bound, a shared stream
     * table, and a body rotation reader by the id a row froze. A missing source is named when the
     * capture refuses instead of being replaced. */
    public record RealSources(LongSupplier worldSeed, RegistrationEpoch registration,
                              OwnerRegistry owners, SharedStreamTable streams,
                              LongSupplier waitBoundNanos,
                              Function<EntityDueWindow.Row, Double> bodyRotation) {

        /** Sources without a body rotation reader cannot bind a reversible state. */
        public RealSources(LongSupplier worldSeed, RegistrationEpoch registration,
                           OwnerRegistry owners, SharedStreamTable streams,
                           LongSupplier waitBoundNanos) {
            this(worldSeed, registration, owners, streams, waitBoundNanos, null);
        }
    }

    /** Binds the sources of one real level and the shared stream table its random already owns. */
    public static RealSources fromLevel(net.minecraft.server.level.ServerLevel level,
                                        SharedStreamTable streams) {
        LongSupplier seed = null;
        Function<EntityDueWindow.Row, Double> bodyRotation = null;
        if (level != null) {
            seed = level::getSeed;
            // A due row omits body rotation; read it from the live entity of that id. A missing
            // entity refuses the capture below instead of borrowing the row yaw.
            bodyRotation = row -> {
                net.minecraft.world.entity.Entity entity = level.getEntity(row.entityId());
                return entity == null ? null : (double) entity.getVisualRotationYInDegrees();
            };
        }
        KernelModule kernel = KernelModule.instance();
        return new RealSources(seed, kernel.registrationEpoch(), kernel.owners(), streams,
            () -> Math.max(0L, KernelSettings.waitBoundMs()) * 1_000_000L, bodyRotation);
    }

    /** Captures one due window; an empty window or an unavailable source is refused by name. */
    public static Result fromDueWindow(EntityDueCapture.Window window, long planTick,
                                       RealSources sources) {
        if (window == null) {
            return new Result(null, "unbound:no-due-window");
        }
        EntityDueWindow.World world = null;
        for (EntityDueWindow.World candidate : window.worlds()) {
            if (candidate != null && !candidate.rows().isEmpty()) {
                world = candidate;
                break;
            }
        }
        if (world == null) {
            return new Result(null, "unbound:no-arrival-rows");
        }
        if (sources == null) {
            return new Result(null, "NOT_MEASURED:real-sources");
        }
        if (sources.worldSeed() == null) {
            return new Result(null, "NOT_MEASURED:worldSeed");
        }
        long worldSeed;
        try {
            worldSeed = sources.worldSeed().getAsLong();
        } catch (Throwable unavailable) {
            return new Result(null, "NOT_MEASURED:worldSeed");
        }
        if (sources.registration() == null) {
            return new Result(null, "NOT_MEASURED:registrationVersion");
        }
        if (sources.owners() == null) {
            return new Result(null, "NOT_MEASURED:owners");
        }
        if (sources.streams() == null) {
            return new Result(null, "NOT_MEASURED:streamState");
        }
        if (sources.waitBoundNanos() == null) {
            return new Result(null, "NOT_MEASURED:arrival-deadline");
        }
        long waitBoundNanos;
        try {
            waitBoundNanos = sources.waitBoundNanos().getAsLong();
        } catch (Throwable unavailable) {
            return new Result(null, "NOT_MEASURED:arrival-deadline");
        }
        if (waitBoundNanos < 0L) {
            return new Result(null, "NOT_MEASURED:arrival-deadline");
        }
        // A due world is named by its dimension, which is also the world identity a level's owner
        // random uses in its stream keys; the reference stream is the one that key already created.
        StreamKey key = StreamKey.of(worldSeed, world.worldId(), world.worldId(),
            REFERENCE_BUSINESS, 0L);
        SharedStream stream = sources.streams().peek(key);
        if (stream == null) {
            return new Result(null, "NOT_MEASURED:streamState");
        }
        Function<EntityDueWindow.Row, Double> bodyRotation = sources.bodyRotation();
        if (bodyRotation == null) {
            return new Result(null, "NOT_MEASURED:yBodyRot");
        }
        List<ReferenceInput.Arrival> arrivals = new ArrayList<>(world.rows().size());
        Map<Long, Long> initialState = new LinkedHashMap<>();
        Map<Long, ReferenceInput.EntityState> reversibleState = new LinkedHashMap<>();
        long order = 1L;
        for (EntityDueWindow.Row row : world.rows()) {
            long identity = identityOf(row);
            if (identity < 0L) {
                return new Result(null, "NOT_MEASURED:arrival-identity");
            }
            arrivals.add(new ReferenceInput.Arrival(identity, row.entityId(), row.x(), row.y(),
                row.z(), order++, waitBoundNanos));
            initialState.put((long) row.entityId(), stateDigestOf(row));
            double body;
            try {
                Double supplied = bodyRotation.apply(row);
                if (supplied == null || !Double.isFinite(supplied)) {
                    return new Result(null, "NOT_MEASURED:yBodyRot");
                }
                body = supplied;
            } catch (Throwable unavailable) {
                return new Result(null, "NOT_MEASURED:yBodyRot");
            }
            // The row holds the tick counter the entity had on the tick thread. A predicted next
            // tick is a different state and must not be used as the start of a replay.
            reversibleState.put((long) row.entityId(), new ReferenceInput.EntityState(row.x(),
                row.y(), row.z(), row.yaw(), row.pitch(), body, row.vx(), row.vy(), row.vz(),
                row.tickCount()));
        }
        // The entity's own random source, brain, goals, inventory and world clock are not exposed
        // as reversible values; they stay not measured, with no synthetic value substituted.
        ReferenceInput input = ReferenceInput.capture(worldSeed, world.worldId(),
            sources.registration().current(), planTick, sources.owners().activeTokens(),
            arrivals, stream, initialState, reversibleState);
        return new Result(input, "");
    }

    /** Captures the published due window of one plan tick from the live sources. */
    public static Result captureCurrent(long planTick, RealSources sources) {
        EntityDueCapture.Window window = EntityDueCapture.published(planTick);
        if (window == null) {
            return new Result(null, "unbound:due-window-not-armed-or-no-plan");
        }
        return fromDueWindow(window, planTick, sources);
    }

    /** A stable identity from the identity fields the tick thread actually froze. */
    private static long identityOf(EntityDueWindow.Row row) {
        long value = (((long) row.entityId()) * 1_000_003L) ^ row.uuidHigh()
            ^ Long.rotateLeft(row.uuidLow(), 17) ^ ((long) row.tickCount() * 131L)
            ^ Long.rotateLeft(row.hostStep(), 29);
        return value & Long.MAX_VALUE;
    }

    /** A one-way summary of the frozen initial pose and motion, used only to check a live row. */
    private static long stateDigestOf(EntityDueWindow.Row row) {
        long digest = 0x9E3779B97F4A7C15L;
        digest = mix(digest, Double.doubleToRawLongBits(row.x()));
        digest = mix(digest, Double.doubleToRawLongBits(row.y()));
        digest = mix(digest, Double.doubleToRawLongBits(row.z()));
        digest = mix(digest, Double.doubleToRawLongBits(row.yaw()));
        digest = mix(digest, Double.doubleToRawLongBits(row.pitch()));
        digest = mix(digest, Double.doubleToRawLongBits(row.vx()));
        digest = mix(digest, Double.doubleToRawLongBits(row.vy()));
        digest = mix(digest, Double.doubleToRawLongBits(row.vz()));
        digest = mix(digest, row.tickCount());
        digest = mix(digest, row.hostStep());
        digest = mix(digest, row.uuidHigh());
        digest = mix(digest, row.uuidLow());
        return digest & Long.MAX_VALUE;
    }

    private static long mix(long digest, long value) {
        long changed = digest ^ value;
        changed *= 0xFF51AFD7ED558CCDL;
        changed = Long.rotateLeft(changed, 31);
        return changed * 0xC4CEB9FE1A85EC53L;
    }
}
