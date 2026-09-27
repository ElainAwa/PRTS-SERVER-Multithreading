package io.izzel.arclight.common.bridge.core.world.level;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.util.function.BiConsumer;

public interface GameRules_TypeBridge<T extends GameRules.Value<T>> {
    void arclight$setPerWorldCallback(BiConsumer<ServerLevel, T> callback);
    void arclight$runCallback(ServerLevel level, T value);
}
