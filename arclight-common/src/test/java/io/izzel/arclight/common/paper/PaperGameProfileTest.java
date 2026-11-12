/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperGameProfileTest {

    @Test
    void readsTheNameIdAndPropertiesFromTheGameProfile() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        GameProfile game = new GameProfile(id, "Ada");
        game.getProperties().put("textures", new Property("textures", "skin", "sig"));
        PaperGameProfile profile = new PaperGameProfile(game);
        assertEquals("Ada", profile.getName());
        assertEquals(id, profile.getId());
        assertEquals(id, profile.getUniqueId());
        assertTrue(profile.hasProperty("textures"));
        ProfileProperty property = profile.getProperties().iterator().next();
        assertEquals("textures", property.getName());
        assertEquals("skin", property.getValue());
        assertEquals("sig", property.getSignature());
    }

    @Test
    void writesAndRefreshesAreUnsupported() {
        PaperGameProfile profile = new PaperGameProfile(new GameProfile(UUID.randomUUID(), "Ada"));
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
            () -> profile.setName("Bea"));
        assertEquals("UNSUPPORTED", thrown.getMessage());
        assertThrows(UnsupportedOperationException.class, profile::update);
        assertThrows(UnsupportedOperationException.class, profile::clearProperties);
    }
}
