package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Extracts the tail of {@code LivingEntity.tick} after the AI step call behind a program entry, so
 * a split body can defer it until phase B has run and keep the original host order. */
public final class PrtsLivingEntityTickTail implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/LivingEntity";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) {
            return false;
        }
        return TailRegionExtractor.extractProgram(node, "tick", "()V",
            call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "aiStep".equals(call.name) && "()V".equals(call.desc),
            "prts$tickTail",
            "io/izzel/arclight/common/prts/kernel/domain/entity/owner/LivingEntityAiStepStages",
            "io/izzel/arclight/common/prts/kernel/domain/entity/owner/LivingTickTailProgram");
    }
}
