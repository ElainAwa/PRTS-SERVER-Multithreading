/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times one inbound packet decode and reports it to the internal quota counter. The packet is
 * decoded exactly as before; only a span is read around it, and the gate is off by default. */
package io.izzel.arclight.common.prts.fixes.observation;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostGate;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostReadings;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(PacketDecoder.class)
public abstract class PrtsPacketDecodeCostMixin {

    @WrapMethod(method = "decode")
    private void prts$timeDecode(ChannelHandlerContext context, ByteBuf buffer, List<Object> output,
                                 Operation<Void> original) {
        if (!QuotaCostGate.enabled()) {
            original.call(context, buffer, output);
            return;
        }
        long startedAt = System.nanoTime();
        try {
            original.call(context, buffer, output);
        } finally {
            QuotaCostReadings.note(QuotaCostReadings.QuotaClass.NET,
                System.nanoTime() - startedAt);
        }
    }
}
