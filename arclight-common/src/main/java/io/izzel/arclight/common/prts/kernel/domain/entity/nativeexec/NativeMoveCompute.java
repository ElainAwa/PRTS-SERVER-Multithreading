/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import com.google.common.collect.ImmutableList;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/** The vanilla collision equation over a frozen read: the same collider collection and the same
 * axis order as Entity.collideBoundingBox, with the live Level replaced by the host-built
 * CollisionGetter. No simplified stand-in and no second physics model is introduced. */
public final class NativeMoveCompute {

    private NativeMoveCompute() {
    }

    /** Computes the clipped vector for one frozen read on the worker side. */
    public static MoveCollisionResult compute(Entity entity, NativeMoveRead read) {
        return computeWith(entity, read, read.request());
    }

    /** Recomputes the same vanilla equation with a real move argument against a frozen read. The
     * caller must have checked the read coverage; no live Level is consulted here. */
    public static MoveCollisionResult computeWith(Entity entity, NativeMoveRead read, Vec3 request) {
        if (request == null || !read.covers(request)) {
            throw new IllegalArgumentException("a request outside the frozen coverage");
        }
        if (request.lengthSqr() == 0.0D) {
            return new MoveCollisionResult(request, request);
        }
        AABB expanded = read.box().expandTowards(request);
        List<VoxelShape> colliders = collect(entity, read.region(), read.entityCollisions(), expanded);
        return new MoveCollisionResult(request, collideWithShapes(request, read.box(), colliders));
    }

    private static List<VoxelShape> collect(Entity entity, net.minecraft.world.level.CollisionGetter getter,
                                            List<VoxelShape> entities, AABB box) {
        ImmutableList.Builder<VoxelShape> shapes = ImmutableList.builderWithExpectedSize(entities.size() + 1);
        shapes.addAll(entities);
        WorldBorder border = getter.getWorldBorder();
        if (entity != null && border.isInsideCloseToBorder(entity, box)) {
            shapes.add(border.getCollisionShape());
        }
        shapes.addAll(getter.getBlockCollisions(entity, box));
        return shapes.build();
    }

    private static Vec3 collideWithShapes(Vec3 vec, AABB box, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return vec;
        }
        double x = vec.x;
        double y = vec.y;
        double z = vec.z;
        if (y != 0.0D) {
            y = Shapes.collide(Direction.Axis.Y, box, shapes, y);
            if (y != 0.0D) {
                box = box.move(0.0D, y, 0.0D);
            }
        }
        boolean zFirst = Math.abs(x) < Math.abs(z);
        if (zFirst && z != 0.0D) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
            if (z != 0.0D) {
                box = box.move(0.0D, 0.0D, z);
            }
        }
        if (x != 0.0D) {
            x = Shapes.collide(Direction.Axis.X, box, shapes, x);
            if (!zFirst && x != 0.0D) {
                box = box.move(x, 0.0D, 0.0D);
            }
        }
        if (!zFirst && z != 0.0D) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
        }
        return new Vec3(x, y, z);
    }
}