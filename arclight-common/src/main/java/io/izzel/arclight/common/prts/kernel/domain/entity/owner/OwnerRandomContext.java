/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/** The owner-body answer to which keyed shared stream a level draw belongs to. The context is set
 * only while one owner body runs and names a stable world seed and dimension, the business kind and
 * the object identity; it is never a thread id, an object hash or the clock. */
final class OwnerRandomContext {

    /** One owner body's stream components. */
    record Row(long worldSeed, String worldIdentity, String dimension, String business,
               long objectIdentity) {
    }

    private static final ThreadLocal<Row> CURRENT = new ThreadLocal<>();

    private OwnerRandomContext() {
    }

    static void enter(ServerLevel level, Entity entity, String business) {
        if (level == null || entity == null || business == null || business.isEmpty()) {
            CURRENT.remove();
            return;
        }
        String dimension = level.dimension().location().toString();
        CURRENT.set(new Row(level.getSeed(), dimension, dimension, business,
            objectIdentity(entity.getUUID())));
    }

    static void enter(Row row) {
        CURRENT.set(row);
    }

    static void exit() {
        CURRENT.remove();
    }

    static Row current() {
        return CURRENT.get();
    }

    /** A UUID pair folded into one stable long; never the Java object hash or an identity hash. */
    static long objectIdentity(UUID uuid) {
        return uuid.getMostSignificantBits()
            ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 32);
    }
}
