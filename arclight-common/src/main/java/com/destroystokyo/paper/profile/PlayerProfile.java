/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.destroystokyo.paper.profile;

import org.bukkit.profile.PlayerTextures;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Paper's profile view. Reads come from the server profile. Writes are unsupported.
 */
public interface PlayerProfile extends org.bukkit.profile.PlayerProfile {

    @Override
    String getName();

    String setName(String name);

    UUID getId();

    UUID setId(UUID uuid);

    @Override
    PlayerTextures getTextures();

    @Override
    void setTextures(PlayerTextures textures);

    Set<ProfileProperty> getProperties();

    boolean hasProperty(String property);

    void setProperty(ProfileProperty property);

    void setProperties(Collection<ProfileProperty> properties);

    boolean removeProperty(String property);

    default boolean removeProperty(ProfileProperty property) {
        return this.removeProperty(property.getName());
    }

    default boolean removeProperties(Collection<ProfileProperty> properties) {
        boolean removed = false;
        for (ProfileProperty property : properties) {
            if (this.removeProperty(property)) {
                removed = true;
            }
        }
        return removed;
    }

    void clearProperties();

    @Override
    boolean isComplete();

    boolean completeFromCache();

    boolean completeFromCache(boolean onlineMode);

    boolean completeFromCache(boolean lookupUUID, boolean onlineMode);

    default boolean complete() {
        return this.complete(true);
    }

    boolean complete(boolean textures);

    boolean complete(boolean textures, boolean onlineMode);

    default boolean hasTextures() {
        return this.hasProperty("textures");
    }
}
