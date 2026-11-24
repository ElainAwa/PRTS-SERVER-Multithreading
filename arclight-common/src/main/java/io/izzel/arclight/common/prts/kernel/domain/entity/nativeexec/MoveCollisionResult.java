/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** The outcome of one staged collision read: the request it answers and the clipped vector the
 * original move would have consumed. */
public record MoveCollisionResult(Vec3 request, Vec3 clipped) {

    public MoveCollisionResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(clipped, "clipped");
    }
}
