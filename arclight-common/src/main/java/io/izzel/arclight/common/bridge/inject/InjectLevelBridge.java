package io.izzel.arclight.common.bridge.inject;

import org.bukkit.craftbukkit.v.CraftWorld;

public interface InjectLevelBridge {

    /** Non-throwing accessor for startup hooks: null when this level has no CraftWorld. */
    default CraftWorld bridge$getWorldOrNull() {
        return null;
    }

    default CraftWorld bridge$getWorld() {
        throw new IllegalStateException("Not implemented");
    }
}
