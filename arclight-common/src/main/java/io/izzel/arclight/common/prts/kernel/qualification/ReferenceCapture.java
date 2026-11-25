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
import java.util.function.LongSupplier;

/**
 * Captures one due window as the fixed input of a reference run from live sources only. Every field
 * comes from a production object or is named as not measured; a tick counter, a world incarnation, a
 * constant owner count and an empty state are never used in place of a real value.
 */
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
     * The live sources one capture reads. The seed, registration, owner registry and wait bound are
     * bound to production objects; the stream table is one a level random already created. A source
     * that cannot be supplied is named when the capture refuses instead of being replaced.
     */
    public record RealSources(LongSupplier worldSeed, RegistrationEpoch registration,
                              OwnerRegistry owners, SharedStreamTable streams,
                              LongSupplier waitBoundNanos) {
    }

    /** Binds the sources of one real level and the shared stream table its random already owns. */
    public static RealSources fromLevel(net.minecraft.server.level.ServerLevel level,
                                        SharedStreamTable streams) {
        LongSupplier seed = null;
        if (level != null) {
            seed = level::getSeed;
        }
        KernelModule kernel = KernelModule.instance();
        return new RealSources(seed, kernel.registrationEpoch(), kernel.owners(), streams,
            () -> Math.max(0L, KernelSettings.waitBoundMs()) * 1_000_000L);
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
        List<ReferenceInput.Arrival> arrivals = new ArrayList<>(world.rows().size());
        Map<Long, Long> initialState = new LinkedHashMap<>();
        long order = 1L;
        for (EntityDueWindow.Row row : world.rows()) {
            long identity = identityOf(row);
            if (identity < 0L) {
                return new Result(null, "NOT_MEASURED:arrival-identity");
            }
            arrivals.add(new ReferenceInput.Arrival(identity, row.entityId(), row.x(), row.y(),
                row.z(), order++, waitBoundNanos));
            initialState.put((long) row.entityId(), stateDigestOf(row));
        }
        ReferenceInput input = ReferenceInput.capture(worldSeed, world.worldId(),
            sources.registration().current(), planTick, sources.owners().activeTokens(),
            arrivals, stream, initialState);
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

    /** A stable summary of the frozen initial pose and motion, keyed by the row's entity id. */
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
