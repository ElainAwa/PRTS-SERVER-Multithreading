package io.izzel.arclight.common.bridge.core.world.entity.monster;

import io.izzel.arclight.common.bridge.core.world.entity.MobBridge;
import net.minecraft.world.entity.player.Player;

public interface CreeperBridge extends MobBridge {

    void bridge$setPowered(boolean power);

    Player arclight$creeperIgniter();

    float arclight$eventRadius();

    boolean arclight$eventFire();
}
