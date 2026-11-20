package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Extracts the tail of {@code Mob.tick} after the living tick call; it owns its own locals. */
public final class PrtsMobTickTail implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/Mob";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) {
            return false;
        }
        return TailRegionExtractor.extractProgram(node, "tick", "()V",
            call -> call.getOpcode() == Opcodes.INVOKESPECIAL
                && "net/minecraft/world/entity/LivingEntity".equals(call.owner)
                && "tick".equals(call.name) && "()V".equals(call.desc),
            "prts$mobTickTail",
            "io/izzel/arclight/common/prts/kernel/domain/entity/owner/MobTickTailStages",
            "io/izzel/arclight/common/prts/kernel/domain/entity/owner/MobTickTailProgram");
    }
}
