package io.izzel.arclight.common.mixin.core.server.network;

import com.mojang.authlib.GameProfile;
import io.izzel.arclight.common.mod.mixins.annotation.CreateConstructor;
import io.izzel.arclight.common.mod.mixins.annotation.ShadowConstructor;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.SocketAddress;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin extends ServerCommonPacketListenerImplMixin {

    @Mutable
    @Shadow @Final private GameProfile gameProfile;

    @Shadow private ClientInformation clientInformation;

    @ShadowConstructor.Super
    public abstract void arclight$super(MinecraftServer server, Connection connection, CommonListenerCookie cookie, ServerPlayer player);

    @CreateConstructor
    public void arclight$constructor(MinecraftServer server, Connection connection, CommonListenerCookie cookie, ServerPlayer player) {
        arclight$super(server, connection, cookie, player);
        this.gameProfile = cookie.gameProfile();
        this.clientInformation = cookie.clientInformation();
    }

    // The links event is raised by the vanilla-scope mixin of this class: a platform whose
    // configuration task runner reads the links from another method would fail the injection check
    // here, because startConfiguration no longer reads them.

    @Redirect(method = "handleConfigurationFinished", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;canPlayerLogin(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/network/chat/Component;"))
    private Component arclight$skipLoginCheck(PlayerList instance, SocketAddress address, GameProfile gameProfile) {
        return null;
    }

    @Redirect(method = "handleConfigurationFinished", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;getPlayerForLogin(Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer arclight$useCurrentPlayer(PlayerList instance, GameProfile p_215625_, ClientInformation p_300548_) {
        this.player.updateOptions(p_300548_);
        return this.player;
    }
}
