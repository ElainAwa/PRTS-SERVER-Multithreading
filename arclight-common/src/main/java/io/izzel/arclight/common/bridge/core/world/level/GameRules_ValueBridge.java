package io.izzel.arclight.common.bridge.core.world.level;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import javax.annotation.Nullable;

public interface GameRules_ValueBridge<T extends GameRules.Value<T>> {
    void arclight$setFrom(T t, @Nullable ServerLevel level);
    void arclight$set(Object value, @Nullable ServerLevel level);
}
