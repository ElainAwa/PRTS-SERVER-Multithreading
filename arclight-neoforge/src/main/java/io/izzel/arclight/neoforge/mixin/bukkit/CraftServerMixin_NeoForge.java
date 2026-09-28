package io.izzel.arclight.neoforge.mixin.bukkit;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.mod.util.BukkitDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.command.CraftBlockCommandSender;
import org.bukkit.craftbukkit.v.entity.CraftEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftServer.class, remap = false)
public abstract class CraftServerMixin_NeoForge {

    /**
     * Registers the commands that were built before this server existed.
     *
     * <p>The dispatcher is constructed during the data pack load, at a point where there is no
     * server to hand a command map from; the wrappers registered then wait in a queue and are
     * flushed here, before plugins are enabled, so a plugin that resolves one of those commands
     * during its own start finds it.</p>
     *
     * @param ci callback handle
     */
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void prts$flushDeferredCommands(CallbackInfo ci) {
        BukkitDispatcher.flushPending();
    }

    @ModifyVariable(method = "dispatchCommand", remap = false, index = 2, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lorg/spigotmc/AsyncCatcher;catchOp(Ljava/lang/String;)V"))
    private String arclight$forge$forgeCommandEvent(String commandLine, CommandSender sender) {
        CommandSourceStack commandSource;
        if (sender instanceof CraftEntity) {
            commandSource = ((CraftEntity) sender).getHandle().createCommandSourceStack();
        } else if (sender == Bukkit.getConsoleSender()) {
            commandSource = ArclightServer.getMinecraftServer().createCommandSourceStack();
        } else if (sender instanceof CraftBlockCommandSender) {
            commandSource = ((CraftBlockCommandSender) sender).getWrapper();
        } else {
            return commandLine;
        }
        StringReader stringreader = new StringReader("/" + commandLine);
        if (stringreader.canRead() && stringreader.peek() == '/') {
            stringreader.skip();
        }
        ParseResults<CommandSourceStack> parse = ArclightServer.getMinecraftServer().getCommands()
                .getDispatcher().parse(stringreader, commandSource);
        CommandEvent event = new CommandEvent(parse);
        if (NeoForge.EVENT_BUS.post(event).isCanceled()) {
            return null;
        } else if (event.getException() != null) {
            return null;
        } else {
            String s = event.getParseResults().getReader().getString();
            return s.startsWith("/") ? s.substring(1) : s;
        }
    }
}
