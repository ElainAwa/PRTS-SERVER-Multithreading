/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static contract of the one delayed-send mixin: it cancels only the HEAD of the host's normal
 * chunk-send pass, reads the host pending set, and never changes a packet or a future. */
class P3A02hSendDelayMixinStaticTest {

    private static final String TARGET = "net/minecraft/server/network/PlayerChunkSender";
    private static final String MIXIN =
        "io/izzel/arclight/common/prts/fixes/pipeline/PrtsChunkSendDelayMixin";
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

    private static ClassNode read(String name) throws IOException {
        try (InputStream stream = P3A02hSendDelayMixinStaticTest.class.getClassLoader()
            .getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    private static AnnotationNode find(ClassNode node, String descriptor) {
        AnnotationNode found = find(node.visibleAnnotations, descriptor);
        return found != null ? found : find(node.invisibleAnnotations, descriptor);
    }

    private static AnnotationNode find(MethodNode node, String descriptor) {
        AnnotationNode found = find(node.visibleAnnotations, descriptor);
        return found != null ? found : find(node.invisibleAnnotations, descriptor);
    }

    private static AnnotationNode find(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) {
            return null;
        }
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return null;
    }

    private static List<String> strings(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                values.add(String.valueOf(entry));
            }
        } else if (value != null) {
            values.add(String.valueOf(value));
        }
        return values;
    }

    private static List<AnnotationNode> ats(AnnotationNode inject) {
        Object raw = value(inject, "at");
        List<AnnotationNode> list = new ArrayList<>();
        if (raw instanceof AnnotationNode single) {
            list.add(single);
        } else if (raw instanceof List<?> values) {
            for (Object entry : values) {
                if (entry instanceof AnnotationNode node) {
                    list.add(node);
                }
            }
        }
        return list;
    }

    @Test
    void theMixinCancelsOnlyTheSendPassHeadAndReadsTheHostPendingSet() throws Exception {
        ClassNode target = read(TARGET);
        MethodNode method = target.methods.stream()
            .filter(m -> m.name.equals("sendNextChunks")).findFirst().orElseThrow();
        assertEquals(1L, target.methods.stream().filter(m -> m.name.equals("sendNextChunks")).count());
        ClassNode mixin = read(MIXIN);

        AnnotationNode mixinAnnotation = find(mixin, MIXIN_ANNOTATION);
        assertNotNull(mixinAnnotation);
        boolean targets = false;
        if (value(mixinAnnotation, "value") instanceof List<?> list) {
            for (Object entry : list) {
                if (Type.getObjectType(TARGET).equals(entry)) {
                    targets = true;
                }
            }
        }
        assertTrue(targets);

        MethodNode handler = null;
        AnnotationNode inject = null;
        for (MethodNode candidate : mixin.methods) {
            AnnotationNode annotation = find(candidate, INJECT);
            if (annotation != null) {
                handler = candidate;
                inject = annotation;
            }
        }
        assertNotNull(handler);
        assertNotNull(inject);
        assertEquals(List.of("sendNextChunks"), strings(value(inject, "method")));
        assertEquals(1, value(inject, "require"));
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        List<AnnotationNode> at = ats(inject);
        assertEquals(1, at.size());
        assertEquals("HEAD", String.valueOf(value(at.get(0), "value")));

        Type[] targetArgs = Type.getArgumentTypes(method.desc);
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        assertEquals(targetArgs.length + 1, handlerArgs.length);
        for (int index = 0; index < targetArgs.length; index++) {
            assertEquals(targetArgs[index], handlerArgs[index]);
        }
        assertEquals(Type.VOID_TYPE, Type.getReturnType(handler.desc));

        FieldNode pending = null;
        for (FieldNode field : mixin.fields) {
            if (field.name.equals("pendingChunks")) {
                pending = field;
            }
        }
        assertNotNull(pending);
        assertEquals("Lit/unimi/dsi/fastutil/longs/LongSet;", pending.desc);
        assertNotNull(find(pending.visibleAnnotations, SHADOW) != null
            || find(pending.invisibleAnnotations, SHADOW) != null);
    }

    @Test
    void theMixinIsRegisteredInTheExistingFixesConfig() throws Exception {
        try (InputStream stream = P3A02hSendDelayMixinStaticTest.class.getClassLoader()
            .getResourceAsStream("prts-fixes.mixins.json")) {
            assertNotNull(stream);
            String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("pipeline.PrtsChunkSendDelayMixin"));
        }
    }
}
