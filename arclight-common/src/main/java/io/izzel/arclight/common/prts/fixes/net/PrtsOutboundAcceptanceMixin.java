/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.net;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.common.prts.kernel.net.OutboundAcceptance;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Acceptance at the two real write calls inside doSendPacket. FORWARD calls the original write
 * once with its own future, a deferred request returns a same-loop placeholder that the real future
 * completes once while the original listener stays owned by doSendPacket, and a refused request
 * returns a failed future so that same listener runs once. Off unless the product property is set. */
@Mixin(Connection.class)
public abstract class PrtsOutboundAcceptanceMixin {

    @WrapOperation(method = "doSendPacket",
        at = @At(value = "INVOKE", target =
            "Lio/netty/channel/Channel;write(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;"))
    private ChannelFuture prts$write(Channel instance, Object message, Operation<ChannelFuture> original) {
        return prts$accept(instance, message, false, original);
    }

    @WrapOperation(method = "doSendPacket",
        at = @At(value = "INVOKE", target =
            "Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;"))
    private ChannelFuture prts$writeAndFlush(Channel instance, Object message, Operation<ChannelFuture> original) {
        return prts$accept(instance, message, true, original);
    }

    private ChannelFuture prts$accept(Channel instance, Object message, boolean flush,
                                      Operation<ChannelFuture> original) {
        if (!(message instanceof Packet<?> packet) || !OutboundAcceptance.enabled()) {
            return original.call(instance, message);
        }
        Connection connection = (Connection) (Object) this;
        PacketListener listener = connection.getPacketListener();
        if (!connection.isConnected() || connection.getSending() != PacketFlow.CLIENTBOUND
            || listener == null
            || !OutboundAcceptance.isPlayPhase(listener.getClass().getName())) {
            return original.call(instance, message);
        }
        OutboundAcceptance state = OutboundAcceptance.of(connection, instance);
        ChannelPromise placeholder = instance.newPromise();
        OutboundAcceptance.Result result = state.offer(packet, flush, placeholder);
        if (result == OutboundAcceptance.Result.FORWARD) {
            return original.call(instance, message);
        }
        if (result == OutboundAcceptance.Result.ACCEPT_DEFERRED) {
            return placeholder;
        }
        return instance.newFailedFuture(new IllegalStateException("outbound-reject"));
    }

    @Inject(method = "flushChannel", at = @At("HEAD"))
    private void prts$flushQueued(CallbackInfo ci) {
        OutboundAcceptance.flushQueued(this);
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void prts$dropOnDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        OutboundAcceptance.drop(this);
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void prts$dropOnInactive(CallbackInfo ci) {
        OutboundAcceptance.drop(this);
    }
}
