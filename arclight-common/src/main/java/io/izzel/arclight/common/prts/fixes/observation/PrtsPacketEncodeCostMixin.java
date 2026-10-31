/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times one outbound packet encode and reports it to the internal quota counter. The packet is
 * encoded exactly as before; only a span is read around it, and the gate is off by default. */
package io.izzel.arclight.common.prts.fixes.observation;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostGate;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostReadings;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(PacketEncoder.class)
public abstract class PrtsPacketEncodeCostMixin {

    @WrapMethod(method = "encode")
    private void prts$timeEncode(ChannelHandlerContext context, Packet<?> packet, ByteBuf buffer,
                                 Operation<Void> original) {
        if (!QuotaCostGate.enabled()) {
            original.call(context, packet, buffer);
            return;
        }
        long startedAt = System.nanoTime();
        try {
            original.call(context, packet, buffer);
        } finally {
            QuotaCostReadings.note(QuotaCostReadings.QuotaClass.NET,
                System.nanoTime() - startedAt);
        }
    }
}
