/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Reads the frozen brain's sensor table so the wave can stage a neighbour scan only for the rows
 * whose sensor will actually run this tick. */
@Mixin(Brain.class)
public interface PrtsBrainSensorsAccessor {

    @Accessor("sensors")
    Map<SensorType<? extends Sensor<?>>, Sensor<?>> prts$sensors();
}