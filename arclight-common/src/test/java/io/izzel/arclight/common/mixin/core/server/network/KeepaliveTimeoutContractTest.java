package io.izzel.arclight.common.mixin.core.server.network;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline selector matrix for the keepalive timeout: PacketFixer consumes the 15000L constant,
 * Arclight must use the keepAliveTime field instead, so Arclight-only, PacketFixer-only and the
 * combined set each keep a live selector. */
public class KeepaliveTimeoutContractTest {

    private static String resource(String name) throws Exception {
        try (InputStream in = KeepaliveTimeoutContractTest.class.getResourceAsStream(name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static ClassNode vanilla() throws Exception {
        try (InputStream in = KeepaliveTimeoutContractTest.class.getResourceAsStream(
            "/net/minecraft/server/network/ServerCommonPacketListenerImpl.class")) {
            assertNotNull(in, "vanilla class must be on the test classpath");
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(candidate -> candidate.name.equals(name))
            .findFirst().orElse(null);
    }

    private static int countConst15000(ClassNode node) {
        int found = 0;
        for (AbstractInsnNode instruction : method(node, "keepConnectionAlive").instructions) {
            if (instruction instanceof LdcInsnNode ldc && Long.valueOf(15000L).equals(ldc.cst)) {
                found++;
            }
        }
        return found;
    }

    private static int countKeepAliveTimeGet(ClassNode node) {
        int found = 0;
        for (AbstractInsnNode instruction : method(node, "keepConnectionAlive").instructions) {
            if (instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD
                && field.name.equals("keepAliveTime") && field.desc.equals("J")) {
                found++;
            }
        }
        return found;
    }

    @Test
    void vanillaMethodExposesBothIndependentSelectors() throws Exception {
        ClassNode vanilla = vanilla();
        assertEquals(1, countConst15000(vanilla), "PacketFixer's 15000L selector");
        assertEquals(1, countKeepAliveTimeGet(vanilla), "Arclight's keepAliveTime selector");
    }

    @Test
    void arclightMovedOffTheContendedConstant() throws Exception {
        String mixin = resource(
            "/io/izzel/arclight/common/mixin/core/server/network/ServerCommonPacketListenerImplMixin.class");
        assertTrue(mixin.contains("keepAliveTime"), "Arclight reads the real keepalive variable");
        assertTrue(mixin.contains("ServerCommonPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"), "Arclight hooks the timeout decision point");
        assertTrue(mixin.contains("Lorg/spongepowered/asm/mixin/injection/Redirect;"));
        assertFalse(mixin.contains("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;"),
            "Arclight must not compete for the 15000L constant");
        assertTrue(mixin.contains("Lorg/spongepowered/asm/mixin/injection/Redirect;"),
            "decision point must be a redirect");
        String core = resource("/mixins.arclight.core.json");
        assertTrue(core.contains("server.network.ServerCommonPacketListenerImplMixin"));
    }
}

