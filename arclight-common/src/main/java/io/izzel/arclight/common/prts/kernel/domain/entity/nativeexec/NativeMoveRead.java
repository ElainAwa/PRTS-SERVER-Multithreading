/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Objects;

/** The immutable input of one collision read, frozen on the Server thread after phase A. A
 * worker only reads this value and the host-built region it names; it never touches a live Level. */
public record NativeMoveRead(String rowKey, Vec3 request, AABB box, CollisionGetter region,
                             List<VoxelShape> entityCollisions, float maxUpStep, boolean onGround) {

    public NativeMoveRead {
        Objects.requireNonNull(rowKey, "rowKey");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(region, "region");
        entityCollisions = List.copyOf(entityCollisions);
    }

    /** Whether a live move request is the one this read was frozen for; a mismatch keeps the row C. */
    public boolean matches(Vec3 live) {
        return live != null && request.x == live.x && request.y == live.y && request.z == live.z;
    }
}