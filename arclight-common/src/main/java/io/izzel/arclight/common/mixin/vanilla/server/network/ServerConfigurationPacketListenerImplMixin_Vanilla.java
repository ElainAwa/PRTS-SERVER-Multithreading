package io.izzel.arclight.common.mixin.vanilla.server.network;

import io.izzel.arclight.common.bridge.core.server.network.ServerCommonPacketListenerImplBridge;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.CraftServerLinks;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerLinksSendEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Raises the links event where the vanilla start of the configuration reads the server links.
 * A platform that runs the configuration through its own task runner reads them from another
 * method and carries a decoration of its own, so this one belongs to the configuration that
 * describes the vanilla shape: kept in a shared configuration it fails the injection check on
 * that platform, where the named method no longer reads the links at all.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin_Vanilla
    implements ServerCommonPacketListenerImplBridge {

    @Decorate(method = "startConfiguration", require = 0,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;serverLinks"
                + "()Lnet/minecraft/server/ServerLinks;"))
    private ServerLinks arclight$sendLinksEvent(MinecraftServer instance) throws Throwable {
        var links = (ServerLinks) DecorationOps.callsite().invoke(instance);
        var wrapper = new CraftServerLinks(links);
        var event = new PlayerLinksSendEvent((Player) bridge$getPlayer().bridge$getBukkitEntity(), wrapper);
        Bukkit.getPluginManager().callEvent(event);
        return wrapper.getServerLinks();
    }
}
