package io.izzel.arclight.common.mixin.core.world.entity.monster;

import io.izzel.arclight.common.mod.ArclightMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline mechanism experiment: the old inject-only path leaves the seven-argument call in a
 * handler method (0 matches in the body); the preApply bytecode pass must put it into the real
 * explodeCreeper instruction list (1 match), while the six-argument call stays for third parties. */
public class CreeperExplosionContractTest {

    private static final String SIX = "(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)Lnet/minecraft/world/level/Explosion;";
    private static final String SEVEN = "(Lnet/minecraft/world/entity/Entity;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)Lnet/minecraft/world/level/Explosion;";

    private static String resource(String name) throws Exception {
        try (InputStream in = CreeperExplosionContractTest.class.getResourceAsStream(name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static ClassNode creeperNode() throws Exception {
        try (InputStream in = CreeperExplosionContractTest.class.getResourceAsStream(
            "/net/minecraft/world/entity/monster/Creeper.class")) {
            assertNotNull(in, "vanilla Creeper class must be on the test classpath");
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode explodeCreeper(ClassNode node) {
        return node.methods.stream()
            .filter(method -> method.name.equals("explodeCreeper") && method.desc.equals("()V"))
            .findFirst().orElse(null);
    }

    private static int count(ClassNode node, String descriptor) {
        MethodNode method = explodeCreeper(node);
        int found = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/world/level/Level")
                && call.name.equals("explode") && call.desc.equals(descriptor)) {
                found++;
            }
        }
        return found;
    }

    @Test
    void preApplyWritesSevenArgCallIntoTheRealMethodBody() throws Exception {
        ClassNode vanilla = creeperNode();
        assertEquals(0, count(vanilla, SEVEN), "old path: no seven-argument call in the body");
        assertEquals(1, count(vanilla, SIX), "six-argument call site stays");
        ArclightMixinPlugin plugin = new ArclightMixinPlugin();
        plugin.preApply("net.minecraft.world.entity.monster.Creeper", vanilla,
            "io.izzel.arclight.common.mixin.core.world.entity.monster.CreeperEarlyHook", null);
        assertEquals(1, count(vanilla, SEVEN), "preApply must add exactly one seven-argument call");
        assertEquals(1, count(vanilla, SIX), "six-argument call site must stay for CreeperFirework");
        plugin.preApply("net.minecraft.world.entity.monster.Creeper", vanilla,
            "io.izzel.arclight.common.mixin.core.world.entity.monster.CreeperEarlyHook", null);
        assertEquals(1, count(vanilla, SEVEN), "injection must be idempotent");
    }

    @Test
    void configsKeepHookEarlyAndNoOpRedirectLate() throws Exception {
        String early = resource("/mixins.arclight.creeper7.json");
        assertTrue(early.contains("\"priority\": 500"));
        assertTrue(early.contains("world.entity.monster.CreeperEarlyHook"));
        String core = resource("/mixins.arclight.core.json");
        assertTrue(core.contains("world.entity.monster.CreeperMixin"));
        assertTrue(core.indexOf("CreeperSixArgNoOpMixin") < 0, "no-op redirect must not be early");
        String late = resource("/mixins.arclight.creeperlate.json");
        assertTrue(late.contains("\"priority\": 1500"));
        assertTrue(late.contains("world.entity.monster.CreeperSixArgNoOpMixin"));
    }

    @Test
    void pluginUsesTheEarlyHookNotAnInjectCallback() throws Exception {
        String plugin = resource("/io/izzel/arclight/common/mod/ArclightMixinPlugin.class");
        assertTrue(plugin.contains("CreeperSevenArgInjector"));
        String mixin = resource("/io/izzel/arclight/common/mixin/core/world/entity/monster/CreeperMixin.class");
        assertTrue(!mixin.contains("arclight$redirectExplode"), "redirect must move to the late no-op mixin");
        assertTrue(!mixin.contains("arclight$explodeSevenArgument"), "no inject handler path");
    }

    @Test
    void levelRoutesCreeperSevenArgumentToCustomDamageSource() throws Exception {
        String level = resource("/io/izzel/arclight/common/mixin/core/world/level/LevelMixin.class");
        assertTrue(level.contains(SEVEN));
        assertTrue(level.contains("bridge$customCausingEntityDamager"));
    }
}
