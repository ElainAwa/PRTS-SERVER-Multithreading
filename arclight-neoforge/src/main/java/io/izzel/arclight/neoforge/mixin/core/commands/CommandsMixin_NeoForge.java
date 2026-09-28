package io.izzel.arclight.neoforge.mixin.core.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import io.izzel.arclight.common.bridge.core.commands.CommandsBridge;
import io.izzel.arclight.common.mod.util.BukkitDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.ExecutionCommandSource;
import net.neoforged.neoforge.server.command.CommandHelper;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Function;

@Mixin(Commands.class)
public abstract class CommandsMixin_NeoForge implements CommandsBridge {

    // @formatter:off
    @Mutable @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;
    // @formatter:on

    /**
     * Puts the Bukkit aware dispatcher back in place right after the platform assigns the field.
     *
     * <p>The platform build creates the field at the very beginning of its constructor body, after
     * the constructor code of the shared mixin has already run, so that assignment would undo the
     * replacement and the register override would never be reached. Injecting after the assignment
     * makes every registration that follows go through it, the vanilla pass included - which is why
     * the phase flag starts off and is only raised at the command registration event.</p>
     *
     * @param ci callback handle
     */
    @Inject(method = "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V",
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                    target = "Lnet/minecraft/commands/Commands;dispatcher:Lcom/mojang/brigadier/CommandDispatcher;",
                    shift = At.Shift.AFTER))
    private void prts$installBukkitDispatcher(CallbackInfo ci) {
        BukkitDispatcher dispatcher = new BukkitDispatcher((Commands) (Object) this);
        dispatcher.setConsumer(ExecutionCommandSource.resultConsumer());
        this.dispatcher = dispatcher;
    }

    /**
     * Raises the phase flag before the platform hands the dispatcher to the mod registration event.
     *
     * <p>Everything registered before this point is a vanilla command, which already reaches the
     * Bukkit command map under its {@code minecraft:*} name; only what is registered from here on -
     * mod commands and the platform's own - is wrapped into the map.</p>
     *
     * @param ci callback handle
     */
    @Inject(method = "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/event/EventHooks;onCommandRegister(Lcom/mojang/brigadier/CommandDispatcher;Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V"))
    private void prts$enterModPhase(CallbackInfo ci) {
        ((BukkitDispatcher) this.dispatcher).setModPhase(true);
    }

    @Override
    public <S, T> void bridge$forge$mergeNode(CommandNode<S> sourceNode, CommandNode<T> resultNode,
                                              Map<CommandNode<S>, CommandNode<T>> sourceToResult,
                                              S canUse, Command<T> execute,
                                              Function<SuggestionProvider<S>, SuggestionProvider<T>> sourceToResultSuggestion) {
        CommandHelper.mergeCommandNode(sourceNode, resultNode, sourceToResult, canUse, execute, sourceToResultSuggestion);
    }
}
