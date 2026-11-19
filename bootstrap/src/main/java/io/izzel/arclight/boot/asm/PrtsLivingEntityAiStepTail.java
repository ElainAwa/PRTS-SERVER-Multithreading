package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Extracts the tail of {@code LivingEntity.aiStep} after the server AI step call. */
public final class PrtsLivingEntityAiStepTail implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/LivingEntity";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) {
            return false;
        }
        return TailRegionExtractor.extract(node, "aiStep", "()V",
            call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "serverAiStep".equals(call.name) && "()V".equals(call.desc),
            "prts$aiStepTail");
    }
}
