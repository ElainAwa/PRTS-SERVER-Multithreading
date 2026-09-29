/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsSbwCompat;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Sends a particle only to the players that are close enough to see it.
 *
 * <p>The mod walks every player of the level for each particle call, and its callers are frequent
 * enough that the walk dominates the cost. The packet the mod ultimately sends is unchanged, and
 * the per viewer call is the exact call the mod itself makes, so a viewer inside the radius sees
 * byte for byte what it saw before; only viewers beyond it stop receiving the packet.</p>
 *
 * <p>Only applied while the mod is present; the radius is a constant of this class.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.tools.ParticleTool", remap = false)
public abstract class PrtsSbwParticleToolMixin {

    /**
     * Sends one particle to the players within the radius.
     *
     * @param level level the particle belongs to
     * @param particle particle to send
     * @param x particle position
     * @param y particle position
     * @param z particle position
     * @param count particle count
     * @param xOffset spread
     * @param yOffset spread
     * @param zOffset spread
     * @param speed particle speed
     * @param force whether the particle is forced
     */
    /**
     * @author PRTS
     * @reason the mod walks every player of the level for each particle call
     */
    @Overwrite(remap = false)
    public static <T extends ParticleOptions> void sendParticle(
            ServerLevel level, T particle, double x, double y, double z, int count,
            double xOffset, double yOffset, double zOffset, double speed, boolean force) {
        for (ServerPlayer viewer : level.players()) {
            double dx = viewer.getX() - x;
            double dy = viewer.getY() - y;
            double dz = viewer.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= PrtsSbwCompat.PARTICLE_RADIUS_SQ) {
                level.sendParticles(viewer, particle, force, x, y, z, count,
                    xOffset, yOffset, zOffset, speed);
            }
        }
    }
}
