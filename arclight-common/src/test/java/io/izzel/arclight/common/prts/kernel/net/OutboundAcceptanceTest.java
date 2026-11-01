/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Product contract of fixed-PLAY outbound acceptance: default off, FORWARD keeps the original
 * write, a deferred keep-alive returns a placeholder the real future completes exactly once, a
 * rejection never writes, and the internal queues, state tables and receipt trail stay bounded. */
class OutboundAcceptanceTest {

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private static void configure(long newEpoch, boolean defer) {
        try {
            Field epoch = OutboundAcceptance.class.getDeclaredField("epoch");
            epoch.setAccessible(true);
            epoch.setLong(null, newEpoch);
            Field deferred = OutboundAcceptance.class.getDeclaredField("deferDeferableWrites");
            deferred.setAccessible(true);
            deferred.setBoolean(null, defer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(OutboundAcceptance.PROPERTY);
        configure(1L, false);
        for (EmbeddedChannel channel : channels) {
            if (channel.isOpen()) {
                channel.close();
            }
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }
        channels.clear();
        clearInternal("STATES");
        clearInternal("DROPPING");
        clearInternal("RECEIPTS");
    }

    @Test
    void acceptanceIsOffUntilTheProductPropertyIsEnabled() {
        assertFalse(OutboundAcceptance.enabled());
        System.setProperty(OutboundAcceptance.PROPERTY, "false");
        assertFalse(OutboundAcceptance.enabled());
        System.setProperty(OutboundAcceptance.PROPERTY, "true");
        assertTrue(OutboundAcceptance.enabled());
    }

    @Test
    void onlyTheFixedPlayListenersAreAccepted() {
        assertTrue(OutboundAcceptance.isPlayPhase(
            "net.minecraft.server.network.ServerGamePacketListenerImpl"));
        assertTrue(OutboundAcceptance.isPlayPhase(
            "net.minecraft.client.multiplayer.ClientPacketListener"));
        assertFalse(OutboundAcceptance.isPlayPhase(
            "net.minecraft.server.network.ServerLoginPacketListenerImpl"));
        assertFalse(OutboundAcceptance.isPlayPhase(
            "net.minecraft.server.network.ServerConfigurationPacketListenerImpl"));
        assertFalse(OutboundAcceptance.isPlayPhase(null));
    }

    @Test
    void nonKeepAlivePacketsKeepTheOriginalWrite() {
        EmbeddedChannel channel = openChannel();
        OutboundAcceptance acceptance = OutboundAcceptance.of(new Object(), channel);
        configure(1L, true);

        ChannelPromise placeholder = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.FORWARD,
            acceptance.offer(new ClientboundPingPacket(1), true, placeholder));

        assertFalse(placeholder.isDone());
        assertFalse(channel.outboundMessages().iterator().hasNext());
    }

    @Test
    void keepAliveKeepsTheOriginalWriteWhileTheQueueIsEmpty() {
        EmbeddedChannel channel = openChannel();
        OutboundAcceptance acceptance = OutboundAcceptance.of(new Object(), channel);

        ChannelPromise placeholder = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.FORWARD,
            acceptance.offer(new ClientboundKeepAlivePacket(1L), true, placeholder));

        assertFalse(placeholder.isDone());
        assertFalse(channel.outboundMessages().iterator().hasNext());
    }

    @Test
    void nonFlushingKeepAliveKeepsTheOriginalWrite() {
        EmbeddedChannel channel = openChannel();
        OutboundAcceptance acceptance = OutboundAcceptance.of(new Object(), channel);

        ChannelPromise placeholder = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.FORWARD,
            acceptance.offer(new ClientboundKeepAlivePacket(1L), false, placeholder));

        assertFalse(placeholder.isDone());
        assertFalse(channel.outboundMessages().iterator().hasNext());
    }

    @Test
    void deferredKeepAliveCompletesThePlaceholderOnceThroughTheRealWrite() {
        EmbeddedChannel channel = openChannel();
        Object connection = new Object();
        OutboundAcceptance acceptance = OutboundAcceptance.of(connection, channel);
        configure(1L, true);
        ClientboundKeepAlivePacket packet = new ClientboundKeepAlivePacket(42L);
        ChannelPromise placeholder = channel.newPromise();
        AtomicInteger completions = new AtomicInteger();
        placeholder.addListener(completed -> completions.incrementAndGet());

        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            acceptance.offer(packet, true, placeholder));
        assertFalse(placeholder.isDone());
        assertEquals(0, completions.get());
        assertFalse(channel.outboundMessages().iterator().hasNext());

        OutboundAcceptance.flushQueued(connection);
        channel.runPendingTasks();

        assertTrue(placeholder.isSuccess());
        assertEquals(1, completions.get());
        assertSame(packet, channel.readOutbound());
        assertNull(channel.readOutbound());
        assertFalse(placeholder.tryFailure(new IllegalStateException("again")));
    }

    @Test
    void deferredWritesAreWrittenInSubmissionOrder() {
        EmbeddedChannel channel = openChannel();
        Object connection = new Object();
        OutboundAcceptance acceptance = OutboundAcceptance.of(connection, channel);
        configure(1L, true);
        ClientboundKeepAlivePacket firstPacket = new ClientboundKeepAlivePacket(1L);
        ClientboundKeepAlivePacket secondPacket = new ClientboundKeepAlivePacket(2L);
        ChannelPromise first = channel.newPromise();
        ChannelPromise second = channel.newPromise();

        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            acceptance.offer(firstPacket, true, first));
        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            acceptance.offer(secondPacket, true, second));
        assertFalse(channel.outboundMessages().iterator().hasNext());

        OutboundAcceptance.flushQueued(connection);
        channel.runPendingTasks();

        assertSame(firstPacket, channel.readOutbound());
        assertSame(secondPacket, channel.readOutbound());
        assertNull(channel.readOutbound());
        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
    }

    @Test
    void aFailedRealWriteBridgesTheFailureOnce() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                promise.setFailure(new IllegalStateException("encoder"));
            }
        });
        channels.add(channel);
        Object connection = new Object();
        OutboundAcceptance acceptance = OutboundAcceptance.of(connection, channel);
        configure(1L, true);
        ChannelPromise placeholder = channel.newPromise();
        AtomicInteger completions = new AtomicInteger();
        placeholder.addListener(completed -> completions.incrementAndGet());

        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            acceptance.offer(new ClientboundKeepAlivePacket(1L), true, placeholder));
        OutboundAcceptance.flushQueued(connection);
        channel.runPendingTasks();

        assertTrue(placeholder.isDone());
        assertFalse(placeholder.isSuccess());
        assertEquals(1, completions.get());
        assertEquals("encoder", placeholder.cause().getMessage());
        assertFalse(placeholder.tryFailure(new IllegalStateException("again")));
    }

    @Test
    void aThrowingListenerDoesNotCompleteThePlaceholderTwice() {
        EmbeddedChannel channel = openChannel();
        Object connection = new Object();
        OutboundAcceptance acceptance = OutboundAcceptance.of(connection, channel);
        configure(1L, true);
        ChannelPromise placeholder = channel.newPromise();
        placeholder.addListener(completed -> {
            throw new IllegalStateException("listener-boom");
        });

        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            acceptance.offer(new ClientboundKeepAlivePacket(1L), true, placeholder));
        OutboundAcceptance.flushQueued(connection);
        channel.runPendingTasks();

        assertTrue(placeholder.isSuccess());
        assertFalse(placeholder.tryFailure(new IllegalStateException("again")));
    }

    @Test
    void theQueueBoundRejectsTheWriteThatWouldOverflow() {
        EmbeddedChannel channel = openChannel();
        Object connection = new Object();
        OutboundAcceptance acceptance = OutboundAcceptance.of(connection, channel);
        configure(1L, true);

        for (int i = 0; i < 64; i++) {
            assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
                acceptance.offer(new ClientboundKeepAlivePacket(i), true, channel.newPromise()));
        }
        assertEquals(64, queuedWrites(acceptance));

        ChannelPromise overflow = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.REJECT_NOT_ACCEPTED,
            acceptance.offer(new ClientboundKeepAlivePacket(99L), true, overflow));
        assertFalse(overflow.isDone());
        assertEquals(64, queuedWrites(acceptance));
        assertFalse(channel.outboundMessages().iterator().hasNext());

        OutboundAcceptance.flushQueued(connection);
        channel.runPendingTasks();
        assertEquals(64, outboundCount(channel));
    }

    @Test
    void aNewEpochFailsOnlyTheQueuedWritesOfTheOldEpoch() {
        EmbeddedChannel channel = openChannel();
        Object connection = new Object();
        OutboundAcceptance acceptance = OutboundAcceptance.of(connection, channel);
        configure(1L, true);
        ChannelPromise stale = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            acceptance.offer(new ClientboundKeepAlivePacket(1L), true, stale));

        configure(2L, true);
        ClientboundKeepAlivePacket freshPacket = new ClientboundKeepAlivePacket(2L);
        ChannelPromise fresh = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            acceptance.offer(freshPacket, true, fresh));

        OutboundAcceptance.flushQueued(connection);
        channel.runPendingTasks();

        assertTrue(stale.isDone());
        assertFalse(stale.isSuccess());
        assertTrue(fresh.isSuccess());
        assertSame(freshPacket, channel.readOutbound());
        assertNull(channel.readOutbound());
    }

    @Test
    void dropFailsQueuedPlaceholdersOnceAndRejectsTheConnection() {
        EmbeddedChannel channel = openChannel();
        Object connection = new Object();
        OutboundAcceptance acceptance = OutboundAcceptance.of(connection, channel);
        configure(1L, true);
        ChannelPromise queued = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            acceptance.offer(new ClientboundKeepAlivePacket(1L), true, queued));

        OutboundAcceptance.drop(connection);
        channel.runPendingTasks();

        assertTrue(queued.isDone());
        assertFalse(queued.isSuccess());
        assertFalse(queued.tryFailure(new IllegalStateException("again")));
        assertFalse(channel.outboundMessages().iterator().hasNext());
        assertEquals(0, sizeOf("STATES"));

        OutboundAcceptance replacement = OutboundAcceptance.of(connection, channel);
        ChannelPromise rejected = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.REJECT_NOT_ACCEPTED,
            replacement.offer(new ClientboundKeepAlivePacket(2L), true, rejected));
        assertFalse(rejected.isDone());

        channel.close();
        channel.runPendingTasks();
        assertEquals(0, sizeOf("DROPPING"));
    }

    @Test
    void inactiveChannelGetsAClosedStateThatRejects() {
        EmbeddedChannel channel = openChannel();
        channel.close();
        OutboundAcceptance acceptance = OutboundAcceptance.of(new Object(), channel);

        ChannelPromise placeholder = channel.newPromise();
        assertEquals(OutboundAcceptance.Result.REJECT_NOT_ACCEPTED,
            acceptance.offer(new ClientboundKeepAlivePacket(1L), true, placeholder));
        assertFalse(placeholder.isDone());
        assertFalse(channel.outboundMessages().iterator().hasNext());
    }

    @Test
    void twoChannelsKeepSeparateQueues() {
        EmbeddedChannel firstChannel = openChannel();
        EmbeddedChannel secondChannel = openChannel();
        Object firstConnection = new Object();
        Object secondConnection = new Object();
        OutboundAcceptance firstAcceptance = OutboundAcceptance.of(firstConnection, firstChannel);
        OutboundAcceptance secondAcceptance = OutboundAcceptance.of(secondConnection, secondChannel);
        configure(1L, true);
        ClientboundKeepAlivePacket firstPacket = new ClientboundKeepAlivePacket(1L);
        ClientboundKeepAlivePacket secondPacket = new ClientboundKeepAlivePacket(2L);
        ChannelPromise first = firstChannel.newPromise();
        ChannelPromise second = secondChannel.newPromise();

        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            firstAcceptance.offer(firstPacket, true, first));
        assertEquals(OutboundAcceptance.Result.ACCEPT_DEFERRED,
            secondAcceptance.offer(secondPacket, true, second));

        OutboundAcceptance.flushQueued(firstConnection);
        firstChannel.runPendingTasks();
        assertTrue(first.isSuccess());
        assertFalse(second.isDone());

        OutboundAcceptance.flushQueued(secondConnection);
        secondChannel.runPendingTasks();
        assertTrue(second.isSuccess());
        assertSame(firstPacket, firstChannel.readOutbound());
        assertSame(secondPacket, secondChannel.readOutbound());
    }

    @Test
    void internalReceiptsAndStateTablesStayBounded() {
        EmbeddedChannel closedChannel = openChannel();
        closedChannel.close();
        OutboundAcceptance rejecting = OutboundAcceptance.of(new Object(), closedChannel);
        for (int i = 0; i < 300; i++) {
            assertEquals(OutboundAcceptance.Result.REJECT_NOT_ACCEPTED,
                rejecting.offer(new ClientboundKeepAlivePacket(i), true,
                    closedChannel.newPromise()));
        }
        assertEquals(256, sizeOf("RECEIPTS"));

        List<EmbeddedChannel> dropped = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            EmbeddedChannel channel = openChannel();
            Object connection = new Object();
            OutboundAcceptance.of(connection, channel);
            OutboundAcceptance.drop(connection);
            channel.close();
            channel.runPendingTasks();
            dropped.add(channel);
        }
        for (EmbeddedChannel channel : dropped) {
            channel.runPendingTasks();
        }
        assertEquals(0, sizeOf("STATES"));
        assertEquals(0, sizeOf("DROPPING"));
    }

    private EmbeddedChannel openChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        return channel;
    }

    private static int outboundCount(EmbeddedChannel channel) {
        int count = 0;
        while (channel.readOutbound() != null) {
            count++;
        }
        return count;
    }

    private static int queuedWrites(OutboundAcceptance acceptance) {
        try {
            Field field = OutboundAcceptance.class.getDeclaredField("queue");
            field.setAccessible(true);
            return ((ArrayDeque<?>) field.get(acceptance)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read the deferred queue", e);
        }
    }

    private static int sizeOf(String fieldName) {
        Object value = staticField(fieldName);
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        throw new AssertionError(fieldName + " is not a collection");
    }

    private static void clearInternal(String fieldName) {
        Object value = staticField(fieldName);
        if (value instanceof Map<?, ?> map) {
            map.clear();
        } else if (value instanceof Collection<?> collection) {
            collection.clear();
        } else {
            throw new AssertionError(fieldName + " is not a collection");
        }
    }

    private static Object staticField(String fieldName) {
        try {
            Field field = OutboundAcceptance.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read OutboundAcceptance." + fieldName, e);
        }
    }
}
