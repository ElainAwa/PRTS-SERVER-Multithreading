/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-PLAY outbound write acceptance in front of the two channel writes of
 * {@code Connection.doSendPacket}: FORWARD keeps the original write, ACCEPT_DEFERRED returns a
 * placeholder the real future completes once, and REJECT_NOT_ACCEPTED never writes. State is owned
 * by the channel event loop, and the queue, receipts and state table are bounded. */
public final class OutboundAcceptance {
    /** Product property that arms acceptance. Acceptance is off unless this reads {@code true}. */
    public static final String PROPERTY = "arclight.prts.outboundAcceptance";

    /** The decision the caller has to apply for the write it submitted. */
    public enum Result {
        /** Perform the original write; the original future is the caller's future. */
        FORWARD,
        /** The write is queued; the given placeholder completes once the real write completed. */
        ACCEPT_DEFERRED,
        /** Nothing was written or queued; the caller reports the failure to its listener. */
        REJECT_NOT_ACCEPTED
    }

    private static final int MAX_QUEUED_WRITES = 64;
    private static final int MAX_RECEIPTS = 256;

    private static final Map<Object, OutboundAcceptance> STATES = new ConcurrentHashMap<>();
    private static final Set<Object> DROPPING = ConcurrentHashMap.newKeySet();
    private static final ArrayDeque<String> RECEIPTS = new ArrayDeque<>();

    private static volatile long epoch = 1L;
    private static volatile boolean deferDeferableWrites;

    private final Channel channel;
    private final EventLoop owner;
    private final ArrayDeque<Write> queue = new ArrayDeque<>();
    private boolean closed;
    private boolean draining;

    private record Write(Packet<?> packet, boolean flush, ChannelPromise placeholder, long epoch) {
    }

    private OutboundAcceptance(Channel channel) {
        this.channel = channel;
        this.owner = channel.eventLoop();
    }

    /** Whether the product property arms acceptance; the property is read on every call. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Applies an end-of-window network quota decision. With acceptance disarmed this always
     * clears the flag, so a property-off start cannot leave a stale defer behind. */
    public static void applyNetQuotaDefer(boolean over) {
        if (!enabled()) {
            deferDeferableWrites = false;
            return;
        }
        deferDeferableWrites = over;
    }

    /** Whether the listener class name belongs to the fixed PLAY phase, the only phase accepted here. */
    public static boolean isPlayPhase(String listenerClassName) {
        return "net.minecraft.server.network.ServerGamePacketListenerImpl".equals(listenerClassName)
            || "net.minecraft.client.multiplayer.ClientPacketListener".equals(listenerClassName);
    }

    /** Returns the acceptance state of a connection on a channel, creating it on first use; a
     * dropping connection or an inactive channel gets a closed state instead of a fresh queue. */
    public static OutboundAcceptance of(Object connection, Channel channel) {
        synchronized (STATES) {
            if (DROPPING.contains(connection) || !channel.isActive()) {
                OutboundAcceptance closed = new OutboundAcceptance(channel);
                closed.close("inactive");
                return closed;
            }
            return STATES.computeIfAbsent(connection, ignored -> new OutboundAcceptance(channel));
        }
    }

    /** Ends acceptance for a connection: queued placeholders fail exactly once, the state leaves the
     * table at once, and the dropping marker stays until the channel close completes. */
    public static void drop(Object connection) {
        synchronized (STATES) {
            OutboundAcceptance state = STATES.get(connection);
            if (state == null) {
                return;
            }
            DROPPING.add(connection);
            Runnable closeTask = () -> {
                state.close("disconnect");
                STATES.remove(connection, state);
                if (state.channel.isActive()) {
                    state.channel.closeFuture().addListener(future -> DROPPING.remove(connection));
                } else {
                    DROPPING.remove(connection);
                }
            };
            if (state.owner.inEventLoop()) {
                closeTask.run();
            } else {
                state.owner.execute(closeTask);
            }
        }
    }

    /** Decides one write. On ACCEPT_DEFERRED the placeholder is stored and returned in place of the
     * original future; on REJECT_NOT_ACCEPTED it is untouched because the caller reports the
     * failure through a failed future instead. */
    public Result offer(Packet<?> packet, boolean flush, ChannelPromise placeholder) {
        if (closed || !owner.inEventLoop() || placeholder == null) {
            receipt("REJECTED", packet, "not-owner");
            return Result.REJECT_NOT_ACCEPTED;
        }
        if (!deferable(packet) || (!flush && !deferDeferableWrites)) {
            return Result.FORWARD;
        }
        if (queue.isEmpty() && !deferDeferableWrites) {
            return Result.FORWARD;
        }
        if (queue.size() >= MAX_QUEUED_WRITES) {
            receipt("REJECTED", packet, "queue-full");
            return Result.REJECT_NOT_ACCEPTED;
        }
        queue.addLast(new Write(packet, flush, placeholder, epoch));
        if (!deferDeferableWrites) {
            drain();
        }
        return Result.ACCEPT_DEFERRED;
    }

    /**
     * Flushes the deferred writes of one connection on its event loop, then flushes the channel so
     * that a deferred write ends on the same flush boundary the original path would have used.
     */
    public static void flushQueued(Object connection) {
        OutboundAcceptance state = STATES.get(connection);
        if (state != null) {
            state.owner.execute(() -> {
                state.drain();
                state.channel.flush();
            });
        }
    }

    /** Only the periodic keep-alive is deferred; every other packet keeps the vanilla write. */
    private static boolean deferable(Packet<?> packet) {
        return packet instanceof ClientboundKeepAlivePacket;
    }

    private void drain() {
        if (closed || draining) {
            return;
        }
        draining = true;
        try {
            while (!closed && !queue.isEmpty()) {
                Write write = queue.removeFirst();
                if (write.epoch() != epoch) {
                    receipt("FAILED", write.packet(), "epoch-invalid");
                    write.placeholder().tryFailure(new IllegalStateException("epoch-invalid"));
                    continue;
                }
                ChannelFuture future = write.flush() ? channel.writeAndFlush(write.packet())
                    : channel.write(write.packet());
                bridge(write, future);
            }
        } finally {
            draining = false;
        }
    }

    private void bridge(Write write, ChannelFuture future) {
        future.addListener((ChannelFutureListener) completed -> {
            if (completed.isSuccess()) {
                write.placeholder().trySuccess();
            } else {
                receipt("FAILED", write.packet(), "downstream");
                write.placeholder().tryFailure(completed.cause());
            }
        });
    }

    private void close(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        if (queue.isEmpty()) {
            receipt("CLOSED", null, reason);
            return;
        }
        for (Write write : queue) {
            receipt("CLOSED", write.packet(), reason);
            write.placeholder().tryFailure(new IllegalStateException(reason));
        }
        queue.clear();
    }

    private static void receipt(String kind, Packet<?> packet, String reason) {
        synchronized (RECEIPTS) {
            RECEIPTS.addLast(kind + "|" + reason + "|"
                + (packet == null ? "null" : packet.getClass().getName()));
            while (RECEIPTS.size() > MAX_RECEIPTS) {
                RECEIPTS.removeFirst();
            }
        }
    }

}
