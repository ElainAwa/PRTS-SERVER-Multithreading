/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.nativeext;

import java.util.OptionalLong;

/** The CPU ticket quota one native engine may use: tickets are issued from the shared bounded
 * capacity instead of starting engine threads beside the kernel pool, an over-quota request shrinks
 * the engine parallelism and is refused, and a missing payload answers N/A rather than a zero that
 * would read as a passed run. */
public final class NativeCpuTickets {

    /** What one ticket request did. */
    public record Request(boolean granted, long tickets, String reason) {
    }

    private final NativeEngineOwner.Presence presence;
    private final long capacity;
    private final long queueCap;
    private long issued;
    private long queued;
    private long refused;
    private long released;

    public NativeCpuTickets(NativeEngineOwner.Presence presence, long capacity, long queueCap) {
        if (capacity < 0L || queueCap < 0L) {
            throw new IllegalArgumentException("a ticket quota cannot be negative");
        }
        this.presence = presence;
        this.capacity = capacity;
        this.queueCap = queueCap;
    }

    /** Requests tickets for one native step; only granted tickets may leave the calling site. */
    public synchronized Request request(long tickets) {
        if (presence != NativeEngineOwner.Presence.LOADED) {
            return new Request(false, 0L, "NOT_LOADED_NA");
        }
        if (tickets <= 0L) {
            return new Request(false, 0L, "non-positive");
        }
        if (issued + tickets <= capacity) {
            issued += tickets;
            return new Request(true, tickets, "granted");
        }
        if (queued < queueCap) {
            queued++;
            return new Request(false, 0L, "queued-bounded");
        }
        refused++;
        return new Request(false, 0L, "over-quota");
    }

    /** Releases tickets back to the shared capacity; a value below zero is refused. */
    public synchronized boolean release(long tickets) {
        if (tickets <= 0L || tickets > issued) {
            return false;
        }
        issued -= tickets;
        released += tickets;
        return true;
    }

    /** The issued ticket count, or N/A while the payload is not loaded. */
    public synchronized OptionalLong issuedNa() {
        return presence == NativeEngineOwner.Presence.LOADED ? OptionalLong.of(issued)
            : OptionalLong.empty();
    }

    public synchronized long available() {
        return Math.max(0L, capacity - issued);
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long released() {
        return released;
    }
}
