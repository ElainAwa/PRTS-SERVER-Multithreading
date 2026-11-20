/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mixin.paper;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.izzel.arclight.common.paper.PaperGameProfile;
import org.bukkit.craftbukkit.v.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = Player.class, remap = false)
public interface PaperPlayerProfileApiMixin {
    // Adds getPlayerProfile() on the player.
    // Plugins ask the player for the profile, so a helper type would not match the call.
    // Player is the type plugins hold, so the method is injected there.
    // A player that is not a CraftPlayer throws. The profile reads and refuses writes.

    /** Returns a read-only view of this player's profile. */
    default PlayerProfile getPlayerProfile() {
        if (!(this instanceof CraftPlayer player)) {
            throw new UnsupportedOperationException("UNSUPPORTED");
        }
        return new PaperGameProfile(player.getProfile());
    }
}
