/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static wiring contract for the one existing dedicated-server mixin: the overwrite asks the
 * kernel gate, hands the owner unit to the bounded queue, and the owner safe point pumps it. */
class P3B03GRconWiringTest {

    private static ClassNode read(String name) throws IOException {
        try (InputStream stream = P3B03GRconWiringTest.class.getClassLoader()
            .getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
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
        List<String> found = new ArrayList<>();
        if (method == null) {
            return found;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                found.add(call.owner + "#" + call.name);
            }
        }
        return found;
    }

    private static List<String> fields(MethodNode method) {
        List<String> found = new ArrayList<>();
        if (method == null) {
            return found;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                found.add(field.owner + "#" + field.name);
            }
        }
        return found;
    }

    @Test
    void runCommandUsesTheGateAndTheDeferralQueue() throws Exception {
        ClassNode mixin = read("io/izzel/arclight/common/mixin/core/server/dedicated/DedicatedServerMixin");
        List<String> calls = calls(method(mixin, "runCommand"));
        assertTrue(calls.contains("io/izzel/arclight/common/prts/kernel/KernelModule#rconDeferralWanted"), calls.toString());
        assertTrue(calls.contains("io/izzel/arclight/common/prts/kernel/KernelModule#deferRconCommand"), calls.toString());
        assertTrue(calls.contains("io/izzel/arclight/common/prts/kernel/rcon/RconCommandDeferral$Rejected#gateCleared"),
            calls.toString());
        assertTrue(calls.contains("io/izzel/arclight/common/mixin/core/server/dedicated/DedicatedServerMixin#arclight$runSynchronously"),
            calls.toString());
        ClassNode owner = read("io/izzel/arclight/common/mixin/core/server/dedicated/DedicatedServerMixin$1");
        assertNotNull(method(owner, "prepare"));
        assertNotNull(method(owner, "dispatch"));
        assertNotNull(method(owner, "capture"));
    }

    @Test
    void gateClearedUsesTheOriginalSynchronousPathWithoutDoubleExecution() throws Exception {
        ClassNode mixin = read("io/izzel/arclight/common/mixin/core/server/dedicated/DedicatedServerMixin");
        MethodNode fallback = method(mixin, "arclight$runSynchronously");
        assertNotNull(fallback);
        assertTrue(calls(fallback).stream().anyMatch(call -> call.endsWith("#executeBlocking")),
            calls(fallback).toString());
        MethodNode ownerUnit = method(mixin, "arclight$runOwnerUnit");
        assertNotNull(ownerUnit);
        assertTrue(calls(ownerUnit).contains("net/minecraft/server/rcon/RconConsoleSource#prepareForCommand"),
            calls(ownerUnit).toString());
        assertTrue(calls(ownerUnit).contains("net/minecraft/server/rcon/RconConsoleSource#getCommandResponse"),
            calls(ownerUnit).toString());
    }

    @Test
    void theGateReadsTheRealEventRowAndTheB4Rung() throws Exception {
        ClassNode gate = read("io/izzel/arclight/common/prts/kernel/rcon/RconCommandGate");
        List<String> calls = calls(method(gate, "shouldDefer"));
        assertTrue(calls.contains("io/izzel/arclight/common/prts/kernel/codes/DegradeLevel#ordinal"), calls.toString());
        assertTrue(fields(method(gate, "shouldDefer"))
            .contains("io/izzel/arclight/common/prts/kernel/shares/ShareClass#EVENT"));
        assertTrue(fields(method(gate, "shouldDefer"))
            .contains("io/izzel/arclight/common/prts/kernel/codes/DegradeLevel#B4"));
        ClassNode kernel = read("io/izzel/arclight/common/prts/kernel/KernelModule");
        assertTrue(calls(method(kernel, "rconDeferralWanted"))
            .contains("io/izzel/arclight/common/prts/kernel/rcon/RconCommandGate#shouldDefer"));
    }

    @Test
    void ownerSafePointPumpsAndResetRejectsQueuedRequests() throws Exception {
        ClassNode kernel = read("io/izzel/arclight/common/prts/kernel/KernelModule");
        assertTrue(calls(method(kernel, "markSafePoint"))
            .contains("io/izzel/arclight/common/prts/kernel/KernelModule#pumpRconDeferrals"));
        assertTrue(calls(method(kernel, "clearReadings"))
            .contains("io/izzel/arclight/common/prts/kernel/rcon/RconCommandDeferral#resetQueued"));
        ClassNode lifecycle = read("io/izzel/arclight/common/prts/kernel/WorldLifecycleWiring");
        assertTrue(calls(method(lifecycle, "onServerStopping"))
            .contains("io/izzel/arclight/common/prts/kernel/KernelModule#terminateRconDeferrals"));
    }

    @Test
    void theCompleteUnitNotesEventExactlyOnceInEachPath() throws Exception {
        ClassNode mixin = read("io/izzel/arclight/common/mixin/core/server/dedicated/DedicatedServerMixin");
        assertTrue(calls(method(mixin, "arclight$runOwnerUnit")).stream()
            .anyMatch(call -> call.equals("io/izzel/arclight/common/prts/kernel/rcon/RconEventMeter#note")));
        ClassNode owner = read("io/izzel/arclight/common/mixin/core/server/dedicated/DedicatedServerMixin$1");
        assertEquals(1, calls(method(owner, "capture")).stream()
            .filter(call -> call.equals("io/izzel/arclight/common/prts/kernel/rcon/RconEventMeter#note"))
            .count());
        assertEquals(1, calls(method(owner, "failed")).stream()
            .filter(call -> call.equals("io/izzel/arclight/common/prts/kernel/rcon/RconEventMeter#note"))
            .count());
    }

    @Test
    void c010StaticStillUsesTheOneExistingOverwriteWithNoNewMixinTarget() throws Exception {
        ClassNode mixin = read("io/izzel/arclight/common/mixin/core/server/dedicated/DedicatedServerMixin");
        assertTrue(hasAnnotation(mixin, "Lorg/spongepowered/asm/mixin/Mixin;"));
        MethodNode run = method(mixin, "runCommand");
        assertNotNull(run);
        assertTrue(hasAnnotation(run, "Lorg/spongepowered/asm/mixin/Overwrite;"));
        assertTrue(!hasAnnotation(read("io/izzel/arclight/common/prts/kernel/rcon/RconCommandDeferral"),
            "Lorg/spongepowered/asm/mixin/Mixin;"));
        assertTrue(!hasAnnotation(read("io/izzel/arclight/common/prts/kernel/rcon/RconCommandGate"),
            "Lorg/spongepowered/asm/mixin/Mixin;"));
    }

    private static boolean hasAnnotation(ClassNode node, String descriptor) {
        return hasAnnotation(node.visibleAnnotations, descriptor)
            || hasAnnotation(node.invisibleAnnotations, descriptor);
    }

    private static boolean hasAnnotation(MethodNode node, String descriptor) {
        return hasAnnotation(node.visibleAnnotations, descriptor)
            || hasAnnotation(node.invisibleAnnotations, descriptor);
    }

    private static boolean hasAnnotation(java.util.List<org.objectweb.asm.tree.AnnotationNode> annotations,
                                         String descriptor) {
        if (annotations == null) {
            return false;
        }
        for (org.objectweb.asm.tree.AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theOwnerDispatchStillUsesTheRealBukkitEventAndServerDispatch() throws Exception {
        ClassNode mixin = read("io/izzel/arclight/common/mixin/core/server/dedicated/DedicatedServerMixin");
        List<String> calls = calls(method(mixin, "arclight$dispatchRemoteCommand"));
        assertTrue(calls.contains("org/bukkit/event/server/RemoteServerCommandEvent#<init>"), calls.toString());
        assertTrue(calls.contains("org/bukkit/plugin/PluginManager#callEvent"), calls.toString());
        assertTrue(calls.contains("org/bukkit/event/server/RemoteServerCommandEvent#isCancelled"), calls.toString());
        assertTrue(calls.contains("org/bukkit/event/server/RemoteServerCommandEvent#getCommand"), calls.toString());
        assertTrue(calls.contains("org/bukkit/craftbukkit/v/CraftServer#dispatchServerCommand"), calls.toString());
    }

    @Test
    void theDeferralCountsAreTheRealB4ActionCounters() throws Exception {
        ClassNode kernel = read("io/izzel/arclight/common/prts/kernel/KernelModule");
        List<String> deferred = calls(method(kernel, "noteRconDeferred"));
        assertTrue(deferred.contains("io/izzel/arclight/common/prts/kernel/degrade/DegradeLadder#noteEffective"),
            deferred.toString());
        assertTrue(fields(method(kernel, "noteRconDeferred"))
            .contains("io/izzel/arclight/common/prts/kernel/codes/DegradeLevel#B4"));
        List<String> returned = calls(method(kernel, "maybeReturnRcon"));
        assertTrue(returned.contains("io/izzel/arclight/common/prts/kernel/degrade/DegradeLadder#noteReturned"),
            returned.toString());
        assertTrue(fields(method(kernel, "maybeReturnRcon"))
            .contains("io/izzel/arclight/common/prts/kernel/codes/DegradeLevel#B4"));
    }

    @Test
    void theAdapterHoldsNoMinecraftOrBukkitEvent() throws Exception {
        ClassNode adapter = read("io/izzel/arclight/common/prts/kernel/rcon/RconCommandDeferral");
        for (MethodNode method : adapter.methods) {
            for (String call : calls(method)) {
                assertTrue(!call.contains("net/minecraft") && !call.contains("org/bukkit"), call);
            }
            for (String field : fields(method)) {
                assertTrue(!field.contains("net/minecraft") && !field.contains("org/bukkit"), field);
            }
        }
    }
}
