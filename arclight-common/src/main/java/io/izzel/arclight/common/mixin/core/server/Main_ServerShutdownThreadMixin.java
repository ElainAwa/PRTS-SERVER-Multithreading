package io.izzel.arclight.common.mixin.core.server;

import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spigotmc.AsyncCatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net/minecraft/server/Main$1")
public class Main_ServerShutdownThreadMixin {

    // The shutdown thread holds a DedicatedServer, so the call it makes is the one declared by that
    // type. Naming MinecraftServer here matches no instruction at all: the decorator used to be
    // skipped in silence, and it only became visible when injector validation is turned on.
    @Decorate(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/dedicated/DedicatedServer;halt(Z)V"))
    private void arclight$shutdown(DedicatedServer instance, boolean b) throws Throwable {
        AsyncCatcher.enabled = false;
        DecorationOps.callsite().invoke(instance, instance.isRunning() && b);
    }
}
