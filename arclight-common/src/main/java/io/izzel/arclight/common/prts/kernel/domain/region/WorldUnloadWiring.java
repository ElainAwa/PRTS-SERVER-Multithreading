/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.region;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;

/** The unload fault settlement: before a world drains its intents, its UNRECORDED tickets are
 * settled explicitly with WORLD_LIFECYCLE_DENIED, while another world's tickets and the publishing
 * set stay untouched. The pipeline stays a witness; the save watermark is not authored here. */
public final class WorldUnloadWiring {

    private WorldUnloadWiring() {
    }

    /** The bounded settlement witness of one world: settled, remaining and publishing counts. */
    public record Settlement(int settled, int remaining, int publishing) {
    }

    /** The UNRECORDED tickets of one world, without settling them. */
    public static int pending(CommitReservation reservations, String worldId) {
        if (reservations == null || worldId == null) {
            return 0;
        }
        int pending = 0;
        for (CommitReservation.Pending entry : reservations.pendingDispositions()) {
            CommitTicket ticket = reservations.ticket(entry.ticketId());
            if (ticket != null && worldId.equals(ticket.worldId())) {
                pending++;
            }
        }
        return pending;
    }

    /** Settles one world and reports the bounded witness of what happened. */
    public static Settlement settle(CommitReservation reservations, String worldId) {
        if (reservations == null || worldId == null) {
            return new Settlement(0, 0, 0);
        }
        int settled = settleUnrecorded(reservations, worldId);
        return new Settlement(settled, pending(reservations, worldId), reservations.publishing());
    }

    /** Settles every UNRECORDED ticket of one world; returns how many were settled. */
    public static int settleUnrecorded(CommitReservation reservations, String worldId) {
        if (!KernelSettings.enabled() || reservations == null || worldId == null) {
            return 0;
        }
        int settled = 0;
        for (CommitReservation.Pending pending : reservations.pendingDispositions()) {
            CommitTicket ticket = reservations.ticket(pending.ticketId());
            if (ticket == null || !worldId.equals(ticket.worldId())) {
                continue;
            }
            CommitReservation.FaultResult result = reservations.settleFault(ticket,
                RejectCode.WORLD_LIFECYCLE_DENIED, "world-unload");
            if (result.settled()) {
                settled++;
            }
        }
        return settled;
    }
}
