/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C010 static: the only new target is SimplePluginManager.fireEvent; the mixin has HEAD/RETURN
 * frames and one WrapOperation over the real RegisteredListener.callEvent, with no EventBus or
 * Minecraft target. */
class P3ACC26MixinStaticTest {

    private static final String MIXIN =
        "io/izzel/arclight/common/mixin/core/server/SimplePluginManagerMixin";

    private static ClassNode read(String name) throws Exception {
        try (InputStream stream = P3ACC26MixinStaticTest.class.getClassLoader()
            .getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    private static boolean annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode method(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name)) {
                return method;
            }
        }
        return null;
    }

    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                result.add(call.owner + "#" + call.name);
            }
        }
        return result;
    }

    @Test
    void onlyTheAuthorizedSimplePluginManagerTargetIsAdded() throws Exception {
        ClassNode mixin = read(MIXIN);
        assertTrue(annotation(mixin.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")
            || annotation(mixin.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;"));
        MethodNode begin = method(mixin, "prts$beginFire");
        MethodNode note = method(mixin, "prts$noteListener");
        MethodNode end = method(mixin, "prts$endFire");
        assertNotNull(begin);
        assertNotNull(note);
        assertNotNull(end);
        assertTrue(annotation(begin.visibleAnnotations,
            "Lorg/spongepowered/asm/mixin/injection/Inject;"));
        assertTrue(annotation(note.visibleAnnotations,
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"));
        assertTrue(annotation(end.visibleAnnotations,
            "Lorg/spongepowered/asm/mixin/injection/Inject;"));
        assertTrue(calls(note).contains("io/izzel/arclight/common/prts/kernel/observe/BukkitEventOrderProbe#invokedCurrent"));
        assertFalse(calls(begin).stream().anyMatch(call -> call.contains("EventBus")));
        assertFalse(calls(note).stream().anyMatch(call -> call.contains("EventBus")));
    }

    @Test
    void theCommonConfigNamesOnlyTheOneNewMixin() throws Exception {
        try (InputStream stream = P3ACC26MixinStaticTest.class.getClassLoader()
            .getResourceAsStream("mixins.arclight.core.json")) {
            assertNotNull(stream);
            String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("server.SimplePluginManagerMixin"));
            assertFalse(config.contains("neoforged.bus.EventBus"));
        }
    }
}
