/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.publish;

/** One committed publishable state: the values the tracker may send, under the object revision the
 * publication produced and the session generation it belongs to. The record is immutable; a
 * tracker reads a snapshot, never the live row. */
public record PublishedSnapshot(long revision, long publishedTick, long sessionGeneration,
                                double x, double y, double z, double yaw, double pitch,
                                double velX, double velY, double velZ) {

    public PublishedSnapshot {
        if (revision < 1L) {
            throw new IllegalArgumentException("a published revision starts at one");
        }
        if (publishedTick < 0L || sessionGeneration < 0L) {
            throw new IllegalArgumentException("a publication cannot name a negative generation");
        }
    }
}
