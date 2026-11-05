/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The join-message methods are merged into PlayerJoinEvent when the server starts.
class PaperJoinMessageMixinTest {

    @Test
    void joinMessageUsesThePaperComponentSignature() throws Exception {
        Class<?> mixin = Class.forName("io.izzel.arclight.common.mixin.paper.PaperJoinMessageMixin");
        Method read = mixin.getDeclaredMethod("joinMessage");
        Method write = mixin.getDeclaredMethod("joinMessage", Component.class);
        assertEquals(Component.class, read.getReturnType());
        assertEquals(void.class, write.getReturnType());
    }
}
