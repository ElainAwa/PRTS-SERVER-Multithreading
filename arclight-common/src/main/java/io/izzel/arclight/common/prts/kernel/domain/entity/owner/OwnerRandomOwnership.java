/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

/** Binds one deterministic random stream per owner body. The seed mixes the level seed, the entity
 * identity and the entity's tick count, so the same body on the same row and tick draws the same
 * values, while two rows never share a legacy random source. This is a diagnostic contract until
 * the accepted plan gives the stream its plan-derived seed. */
public final class OwnerRandomOwnership {

    /** The diagnostic switch; off in every shipped build. */
    public static final String PROPERTY = "arclight.prts.ownerThreadRandom";

    private static final ThreadLocal<RandomSource> BOUND = new ThreadLocal<>();

    private OwnerRandomOwnership() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Binds and returns the row stream for one body on this worker; false leaves the shared stream. */
    public static RandomSource begin(ServerLevel level, Entity entity) {
        if (!enabled()) {
            return null;
        }
        long seed = level.getSeed()
            ^ entity.getUUID().getMostSignificantBits()
            ^ Long.rotateLeft(entity.getUUID().getLeastSignificantBits(), 17)
            ^ entity.tickCount
            ^ 0x9E3779B97F4A7C15L;
        RandomSource row = new LegacyRandomSource(seed);
        BOUND.set(row);
        return row;
    }

    public static void end() {
        BOUND.remove();
    }

    public static RandomSource bound() {
        return BOUND.get();
    }
}
