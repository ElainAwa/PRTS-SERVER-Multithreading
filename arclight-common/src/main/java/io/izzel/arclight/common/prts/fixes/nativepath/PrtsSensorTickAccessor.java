/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import net.minecraft.world.entity.ai.sensing.Sensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads one sensor's remaining ticks: a scan is staged only when the sensor will tick now. */
@Mixin(Sensor.class)
public interface PrtsSensorTickAccessor {

    @Accessor("timeToTick")
    long prts$timeToTick();
}