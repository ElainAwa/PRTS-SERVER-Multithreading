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
public record NativeMoveRead(String rowKey, Vec3 request, AABB box, AABB coverage, CollisionGetter region,
                             List<VoxelShape> entityCollisions, float maxUpStep, boolean onGround) {

    /** The host-wide margin a real move may differ from the frozen prediction without leaving the
     * frozen region; a request outside this box is refused and keeps the whole row C. */
    public static final double COVERAGE_MARGIN = 4.0D;

    public NativeMoveRead {
        Objects.requireNonNull(rowKey, "rowKey");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(region, "region");
        entityCollisions = List.copyOf(entityCollisions);
    }

    /** Whether a live move request is the one this read was frozen for. */
    public boolean matches(Vec3 live) {
        return live != null && request.x == live.x && request.y == live.y && request.z == live.z;
    }

    /** Whether the frozen block/entity collision set still covers a real request. The host froze a
     * conservative expansion; a request outside it must not read the live Level to be answered. */
    public boolean covers(Vec3 live) {
        if (live == null) {
            return false;
        }
        AABB query = box.expandTowards(live);
        return coverage.minX <= query.minX && coverage.minY <= query.minY && coverage.minZ <= query.minZ
            && coverage.maxX >= query.maxX && coverage.maxY >= query.maxY && coverage.maxZ >= query.maxZ;
    }

    /** Builds one frozen read with the declared conservative coverage around a predicted request. */
    public static NativeMoveRead of(String rowKey, Vec3 request, AABB box, CollisionGetter region,
                                    List<VoxelShape> entityCollisions, float maxUpStep,
                                    boolean onGround) {
        AABB coverage = box.expandTowards(request).inflate(COVERAGE_MARGIN);
        return new NativeMoveRead(rowKey, request, box, coverage, region, entityCollisions,
            maxUpStep, onGround);
    }
}