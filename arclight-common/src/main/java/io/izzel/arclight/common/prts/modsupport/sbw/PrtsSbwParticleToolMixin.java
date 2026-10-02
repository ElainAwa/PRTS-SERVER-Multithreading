/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sbw;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsModSupportStats;
import io.izzel.arclight.common.prts.support.PrtsSbwCompat;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Sends a particle only to the players close enough to see it. The per-viewer packet is the
 * exact call the mod makes, so a viewer inside the radius sees what it saw before; only
 * viewers beyond it stop receiving it.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.tools.ParticleTool", remap = false)
public abstract class PrtsSbwParticleToolMixin {

    /** @author PRTS @reason the mod walks every player of the level for each particle call */
    @Overwrite(remap = false)
    public static <T extends ParticleOptions> void sendParticle(
            ServerLevel level, T particle, double x, double y, double z, int count,
            double xOffset, double yOffset, double zOffset, double speed, boolean force) {
        boolean narrow = PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT,
            "narrow-sbw-particle-viewers");
        int skipped = 0;
        for (ServerPlayer viewer : level.players()) {
            if (narrow) {
                double dx = viewer.getX() - x;
                double dy = viewer.getY() - y;
                double dz = viewer.getZ() - z;
                if (dx * dx + dy * dy + dz * dz > PrtsSbwCompat.PARTICLE_RADIUS_SQ) {
                    skipped++;
                    continue;
                }
            }
            level.sendParticles(viewer, particle, force, x, y, z, count,
                xOffset, yOffset, zOffset, speed);
        }
        PrtsModSupportStats.count("sbw-particle-viewers-skipped", skipped);
    }
}
