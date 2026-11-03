/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The static contract of the bus mixin: the two dispatch call sites and the one listener call site
 * exist in the target, and the mixin wraps exactly those with two WrapOperations whose method
 * selectors, at targets and handler descriptors match the wrapped calls. No Inject and no THROW at
 * value may remain, because the runtime bus Mixin does not support either on this target. */
class P3EventOrderMixinStaticTest {

    private static final String BUS = "net/neoforged/bus/EventBus";
    private static final String DISPATCH =
        "post(Lnet/neoforged/bus/api/Event;[Lnet/neoforged/bus/api/EventListener;)Lnet/neoforged/bus/api/Event;";
    private static final String POST = "post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;";
    private static final String POST_PHASE =
        "post(Lnet/neoforged/bus/api/EventPriority;Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;";
    private static final String MIXIN_CLASS =
        "io/izzel/arclight/neoforge/mixin/prts/event/PrtsEventBusMixin";

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String WRAP_OPERATION =
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String OPERATION =
        "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

    private record Wrap(AnnotationNode annotation, MethodNode handler) {
    }

    private static ClassNode read(String name) throws IOException {
        try (InputStream stream = P3EventOrderMixinStaticTest.class.getClassLoader()
            .getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    private static List<AnnotationNode> classAnnotations(ClassNode node) {
        List<AnnotationNode> all = new ArrayList<>();
        if (node.visibleAnnotations != null) {
            all.addAll(node.visibleAnnotations);
        }
        if (node.invisibleAnnotations != null) {
            all.addAll(node.invisibleAnnotations);
        }
        return all;
    }

    private static List<AnnotationNode> methodAnnotations(MethodNode method) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            all.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            all.addAll(method.invisibleAnnotations);
        }
        return all;
    }

    private static AnnotationNode find(List<AnnotationNode> annotations, String descriptor) {
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return null;
    }

    private static List<AnnotationNode> atValues(AnnotationNode wrap) {
        Object raw = value(wrap, "at");
        List<AnnotationNode> all = new ArrayList<>();
        if (raw instanceof AnnotationNode single) {
            all.add(single);
        } else if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof AnnotationNode node) {
                    all.add(node);
                }
            }
        }
        return all;
    }

    private static List<String> methodValues(AnnotationNode wrap) {
        Object raw = value(wrap, "method");
        List<String> all = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                all.add(String.valueOf(entry));
            }
        } else if (raw != null) {
            all.add(String.valueOf(raw));
        }
        return all;
    }

    private static String text(AnnotationNode annotation, String key) {
        Object raw = value(annotation, key);
        return raw == null ? "" : String.valueOf(raw);
    }

    private static String dump(ClassNode node) {
        StringBuilder text = new StringBuilder(node.name);
        for (MethodNode method : node.methods) {
            if (method.name.contains("post")) {
                text.append(" | ").append(method.name).append(method.desc);
            }
        }
        return text.toString();
    }

    private static MethodNode find(ClassNode node, String name, Type... arguments) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) {
                continue;
            }
            Type[] actual = Type.getArgumentTypes(method.desc);
            if (actual.length != arguments.length) {
                continue;
            }
            boolean same = true;
            for (int index = 0; index < actual.length; index++) {
                if (!actual[index].equals(arguments[index])) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return method;
            }
        }
        return null;
    }

    private static final Type EVENT = Type.getObjectType("net/neoforged/bus/api/Event");
    private static final Type LISTENER = Type.getObjectType("net/neoforged/bus/api/EventListener");
    private static final Type LISTENER_ARRAY = Type.getType("[Lnet/neoforged/bus/api/EventListener;");
    private static final Type PRIORITY = Type.getObjectType("net/neoforged/bus/api/EventPriority");

    private static MethodInsnNode dispatchCall(MethodNode method, String dispatchDescriptor) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                && call.owner.equals(BUS) && call.name.equals("post")
                && call.desc.equals(dispatchDescriptor)) {
                return call;
            }
        }
        return null;
    }

    private static String calls(MethodNode method) {
        StringBuilder text = new StringBuilder();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                text.append(" | ").append(call.getOpcode()).append(' ').append(call.owner)
                    .append('.').append(call.name).append(call.desc);
            }
        }
        return text.toString();
    }

    private static boolean declares(ClassNode node, String selector) {
        int at = selector.indexOf('(');
        String name = at < 0 ? selector : selector.substring(0, at);
        String descriptor = at < 0 ? null : selector.substring(at);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && (descriptor == null || method.desc.equals(descriptor))) {
                return true;
            }
        }
        return false;
    }

    private static String selector(MethodNode method) {
        return method.name + method.desc;
    }

    @Test
    void theTargetHasTwoDispatchSitesAndOneListenerSite() throws Exception {
        ClassNode bus = read(BUS);
        MethodNode plain = find(bus, "post", EVENT);
        MethodNode phase = find(bus, "post", PRIORITY, EVENT);
        MethodNode dispatch = find(bus, "post", EVENT, LISTENER_ARRAY);
        assertNotNull(plain, () -> "plain missing: " + dump(bus) + " want " + POST);
        assertNotNull(phase, () -> "phase missing: " + dump(bus) + " want " + POST_PHASE);
        assertNotNull(dispatch, () -> "dispatch missing: " + dump(bus) + " want " + DISPATCH);
        assertEquals(POST, selector(plain));
        assertEquals(POST_PHASE, selector(phase));
        assertEquals(DISPATCH, selector(dispatch));
        assertNotNull(dispatchCall(plain, dispatch.desc),
            () -> "public post(Event) calls: " + calls(plain) + " want " + dispatch.desc);
        assertNotNull(dispatchCall(phase, dispatch.desc),
            () -> "public post(EventPriority, Event) calls: " + calls(phase) + " want " + dispatch.desc);

        int invokeSites = 0;
        for (var instruction : dispatch.instructions) {
            if (instruction instanceof MethodInsnNode call
                && call.owner.equals("net/neoforged/bus/api/EventListener")
                && call.name.equals("invoke")) {
                invokeSites++;
            }
        }
        assertEquals(1, invokeSites, "the private dispatch method has exactly one listener invoke site");
    }

    private static List<Wrap> wraps(ClassNode mixin) {
        List<Wrap> all = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            AnnotationNode annotation = find(methodAnnotations(method), WRAP_OPERATION);
            if (annotation != null) {
                all.add(new Wrap(annotation, method));
            }
        }
        return all;
    }

    @Test
    void theMixinHasNoInjectAndNoThrowValue() throws Exception {
        ClassNode mixin = read(MIXIN_CLASS);
        for (MethodNode method : mixin.methods) {
            assertFalse(methodAnnotations(method).stream()
                .anyMatch(annotation -> INJECT.equals(annotation.desc)),
                "the fixed mixin must not use @Inject");
            for (AnnotationNode annotation : methodAnnotations(method)) {
                for (AnnotationNode at : atValues(annotation)) {
                    assertFalse("THROW".equals(text(at, "value")),
                        "a THROW injection point is not supported");
                }
            }
        }
        AnnotationNode mixinAnnotation = find(classAnnotations(mixin), MIXIN_ANNOTATION);
        assertNotNull(mixinAnnotation);
        assertEquals(Boolean.FALSE, value(mixinAnnotation, "remap"), "the bus class is not remapped");
        Object targets = value(mixinAnnotation, "value");
        boolean hasBus = false;
        if (targets instanceof List<?> list) {
            for (Object entry : list) {
                if (Type.getObjectType(BUS).equals(entry)) {
                    hasBus = true;
                }
            }
        }
        assertTrue(hasBus, "the mixin targets the bus class");
    }

    @Test
    void twoWrapOperationsCoverTheThreeRealSites() throws Exception {
        ClassNode mixin = read(MIXIN_CLASS);
        ClassNode bus = read(BUS);
        List<Wrap> wraps = wraps(mixin);
        assertEquals(2, wraps.size(), "one wrapper for the dispatch call sites, one for listener invokes");

        Wrap dispatchWrap = null;
        Wrap invokeWrap = null;
        for (Wrap wrap : wraps) {
            if (methodValues(wrap.annotation()).size() == 2) {
                dispatchWrap = wrap;
            } else {
                invokeWrap = wrap;
            }
        }
        assertNotNull(dispatchWrap);
        assertNotNull(invokeWrap);

        MethodNode plain = find(bus, "post", EVENT);
        MethodNode phase = find(bus, "post", PRIORITY, EVENT);
        MethodNode dispatch = find(bus, "post", EVENT, LISTENER_ARRAY);
        assertNotNull(plain);
        assertNotNull(phase);
        assertNotNull(dispatch);
        List<String> expectedDispatchMethods = List.of(selector(plain), selector(phase));
        assertEquals(expectedDispatchMethods, methodValues(dispatchWrap.annotation()));
        for (String selector : methodValues(dispatchWrap.annotation())) {
            assertTrue(declares(bus, selector), "target must declare " + selector);
        }
        AnnotationNode dispatchAt = atValues(dispatchWrap.annotation()).get(0);
        assertEquals("INVOKE", text(dispatchAt, "value"));
        assertEquals("L" + BUS + ";" + selector(dispatch), text(dispatchAt, "target"));
        assertEquals(Boolean.FALSE, value(dispatchWrap.annotation(), "remap"));
        assertEquals(1, value(dispatchWrap.annotation(), "require"));

        assertEquals(List.of(selector(dispatch)), methodValues(invokeWrap.annotation()));
        assertTrue(declares(bus, selector(dispatch)));
        AnnotationNode invokeAt = atValues(invokeWrap.annotation()).get(0);
        assertEquals("INVOKE", text(invokeAt, "value"));
        assertEquals("Lnet/neoforged/bus/api/EventListener;invoke(Lnet/neoforged/bus/api/Event;)V",
            text(invokeAt, "target"));

        Type[] dispatchArgs = Type.getArgumentTypes(dispatchWrap.handler().desc);
        assertEquals(4, dispatchArgs.length);
        assertEquals(Type.getObjectType(BUS), dispatchArgs[0], "receiver is the bus");
        assertEquals(Type.getObjectType("net/neoforged/bus/api/Event"), dispatchArgs[1]);
        assertEquals(Type.getType("[Lnet/neoforged/bus/api/EventListener;"), dispatchArgs[2]);
        assertEquals(Type.getObjectType(OPERATION), dispatchArgs[3],
            "the last argument is the wrapped operation");
        assertEquals(Type.getObjectType("net/neoforged/bus/api/Event"),
            Type.getReturnType(dispatchWrap.handler().desc), "the wrapper returns the wrapped call type");

        Type[] invokeArgs = Type.getArgumentTypes(invokeWrap.handler().desc);
        assertEquals(3, invokeArgs.length);
        assertEquals(Type.getObjectType("net/neoforged/bus/api/EventListener"), invokeArgs[0]);
        assertEquals(Type.getObjectType("net/neoforged/bus/api/Event"), invokeArgs[1]);
        assertEquals(Type.getObjectType(OPERATION), invokeArgs[2]);
        assertEquals(Type.VOID_TYPE, Type.getReturnType(invokeWrap.handler().desc));
    }
}
