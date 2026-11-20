/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.bukkit.craftbukkit.v.profile.CraftPlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reads a profile from the server's game profile. Writes and cache refresh throw.
 */
public final class PaperGameProfile implements PlayerProfile {

    private final GameProfile profile;
    private CraftPlayerProfile bukkit;

    public PaperGameProfile(GameProfile profile) {
        this.profile = profile;
    }

    @Override
    public String getName() {
        return this.profile.getName();
    }

    @Override
    public UUID getId() {
        return this.profile.getId();
    }

    @Override
    public UUID getUniqueId() {
        return this.getId();
    }

    @Override
    public Set<ProfileProperty> getProperties() {
        Set<ProfileProperty> properties = new HashSet<>();
        for (Property property : this.profile.getProperties().values()) {
            properties.add(new ProfileProperty(property.name(), property.value(), property.signature()));
        }
        return Set.copyOf(properties);
    }

    @Override
    public boolean hasProperty(String property) {
        return this.profile.getProperties().containsKey(property);
    }

    @Override
    public PlayerTextures getTextures() {
        return this.bukkitProfile().getTextures();
    }

    @Override
    public boolean isComplete() {
        return this.profile.getId() != null && this.profile.getName() != null && !this.profile.getName().isEmpty();
    }

    @Override
    public Map<String, Object> serialize() {
        return this.bukkitProfile().serialize();
    }

    @Override
    public PlayerProfile clone() {
        return new PaperGameProfile(this.profile);
    }

    @Override
    public String setName(String name) {
        return unsupported();
    }

    @Override
    public UUID setId(UUID uuid) {
        return unsupported();
    }

    @Override
    public void setTextures(PlayerTextures textures) {
        unsupported();
    }

    @Override
    public void setProperty(ProfileProperty property) {
        unsupported();
    }

    @Override
    public void setProperties(java.util.Collection<ProfileProperty> properties) {
        unsupported();
    }

    @Override
    public boolean removeProperty(String property) {
        return unsupported();
    }

    @Override
    public void clearProperties() {
        unsupported();
    }

    @Override
    public boolean completeFromCache() {
        return unsupported();
    }

    @Override
    public boolean completeFromCache(boolean onlineMode) {
        return unsupported();
    }

    @Override
    public boolean completeFromCache(boolean lookupUUID, boolean onlineMode) {
        return unsupported();
    }

    @Override
    public boolean complete(boolean textures) {
        return unsupported();
    }

    @Override
    public boolean complete(boolean textures, boolean onlineMode) {
        return unsupported();
    }

    @Override
    public CompletableFuture<org.bukkit.profile.PlayerProfile> update() {
        return unsupported();
    }

    private CraftPlayerProfile bukkitProfile() {
        if (this.bukkit == null) {
            this.bukkit = new CraftPlayerProfile(this.profile);
        }
        return this.bukkit;
    }

    private static <T> T unsupported() {
        throw new UnsupportedOperationException("UNSUPPORTED");
    }
}
