/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.izzel.arclight.common.prts.kernel.net.OutboundAcceptance;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaNetWindowTest {

    @BeforeEach
    void setUp() {
        System.clearProperty(OutboundAcceptance.PROPERTY);
        QuotaNetWindow.reset();
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(OutboundAcceptance.PROPERTY);
        QuotaNetWindow.reset();
    }

    @Test
    void anOverWindowArmsDeferredKeepAliveOnlyWhenAcceptanceIsOn() {
        System.setProperty(OutboundAcceptance.PROPERTY, "true");
        QuotaNetWindow.noteTick(0L, 0L, 20L, 0L, 50.0);
        QuotaNetWindow.noteTick(20L, 100_000_000L, 20L, 0L, 50.0);
        assertEquals(1L, QuotaNetWindow.overCount());

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            OutboundAcceptance acceptance = OutboundAcceptance.of(new Object(), channel);
            assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
                acceptance.offer(new ClientboundKeepAlivePacket(1L), true, channel.newPromise()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void anUnderWindowClearsDeferAndKeepsForward() {
        System.setProperty(OutboundAcceptance.PROPERTY, "true");
        QuotaNetWindow.noteTick(0L, 0L, 20L, 0L, 50.0);
        QuotaNetWindow.noteTick(20L, 40_000_000L, 20L, 0L, 50.0);
        assertEquals(0L, QuotaNetWindow.overCount());

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            OutboundAcceptance acceptance = OutboundAcceptance.of(new Object(), channel);
            assertEquals(OutboundAcceptance.Result.FORWARD,
                acceptance.offer(new ClientboundKeepAlivePacket(1L), true, channel.newPromise()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void propertyOffMeasuresButNeverDefers() {
        QuotaNetWindow.noteTick(0L, 0L, 20L, 0L, 50.0);
        QuotaNetWindow.noteTick(20L, 100_000_000L, 20L, 0L, 50.0);
        assertEquals(1L, QuotaNetWindow.overCount());

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            OutboundAcceptance acceptance = OutboundAcceptance.of(new Object(), channel);
            assertEquals(OutboundAcceptance.Result.FORWARD,
                acceptance.offer(new ClientboundKeepAlivePacket(1L), true, channel.newPromise()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void netOverIsPublishedAsTheReadoutKey() {
        QuotaNetWindow.noteTick(0L, 0L, 20L, 0L, 50.0);
        QuotaNetWindow.noteTick(20L, 100_000_000L, 20L, 0L, 50.0);
        assertEquals(1L, QuotaNetWindow.overCount());
        assertTrue(KernelReadings.export(io.izzel.arclight.common.prts.kernel.KernelModule.instance())
            .stream().anyMatch(line -> line.startsWith("quota.net_over=")));
    }

    @Test
    void overDeferRespectsTheBoundedQueueAndCloseFailsQueuedOnce() {
        System.setProperty(OutboundAcceptance.PROPERTY, "true");
        QuotaNetWindow.noteTick(0L, 0L, 20L, 0L, 50.0);
        QuotaNetWindow.noteTick(20L, 100_000_000L, 20L, 0L, 50.0);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Object key = new Object();
            OutboundAcceptance acceptance = OutboundAcceptance.of(key, channel);
            ChannelPromise first = channel.newPromise();
            assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
                acceptance.offer(new ClientboundKeepAlivePacket(1L), true, first));
            for (int i = 0; i < 63; i++) {
                assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
                    acceptance.offer(new ClientboundKeepAlivePacket(2L + i), true, channel.newPromise()));
            }
            assertEquals(OutboundAcceptance.Result.REJECT_NOT_ACCEPTED,
                acceptance.offer(new ClientboundKeepAlivePacket(99L), true, channel.newPromise()));
            OutboundAcceptance.drop(key);
            channel.runPendingTasks();
            assertTrue(first.isDone());
            assertFalse(first.isSuccess());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void warmupWindowDoesNotArmOrCountOver() {
        System.setProperty(OutboundAcceptance.PROPERTY, "true");
        QuotaNetWindow.noteTick(0L, 0L, 20L, 2L, 50.0);
        QuotaNetWindow.noteTick(20L, 100_000_000L, 20L, 2L, 50.0);
        assertEquals(0L, QuotaNetWindow.overCount());
        assertTrue(QuotaNetWindow.overCount() <= 1L);
    }
}
