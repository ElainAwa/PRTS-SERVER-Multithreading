/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.tick;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The wavefront of redstone, observer and piston work: a short pulse is kept in a transition
 * witness even when the first and the last state are equal, a neighbour notification is sent once
 * and is never replayed after a conflict, and a piston move or re-entry is isolated by the
 * wavefront generation it belongs to. */
public final class RedstoneWavefront {

    /** One observed state transition of one position inside one wavefront. */
    public record Transition(long wavefrontGen, long blockPos, int fromState, int toState) {
    }

    /** One sent neighbour notification. */
    public record Notification(long wavefrontGen, String notificationId) {
    }

    /** Why a notification was not sent again. */
    public enum Refusal {
        ACCEPTED,
        DUPLICATE,
        STALE_WAVEFRONT
    }

    private long wavefrontGen;
    private final List<Transition> transitions = new ArrayList<>();
    private final Set<String> sent = new LinkedHashSet<>();
    private final List<Notification> notifications = new ArrayList<>();
    private long duplicates;
    private long stale;
    private long pistonMoves;
    private long reentries;

    /** Starts the next wavefront; a piston re-entry of an older generation is isolated by it. */
    public synchronized long beginWavefront() {
        wavefrontGen++;
        return wavefrontGen;
    }

    /** Records one transition; both edges of a short pulse stay in the witness. */
    public synchronized void transition(long generation, long blockPos, int fromState, int toState) {
        if (generation != wavefrontGen) {
            stale++;
            return;
        }
        transitions.add(new Transition(generation, blockPos, fromState, toState));
    }

    /** Sends one neighbour notification once; a repeated id or an old wavefront never re-sends. */
    public synchronized Refusal notifyNeighbour(long generation, String notificationId) {
        Objects.requireNonNull(notificationId, "notificationId");
        if (generation != wavefrontGen) {
            stale++;
            return Refusal.STALE_WAVEFRONT;
        }
        if (!sent.add(notificationId)) {
            duplicates++;
            return Refusal.DUPLICATE;
        }
        notifications.add(new Notification(generation, notificationId));
        return Refusal.ACCEPTED;
    }

    /** Admits one piston move of the current wavefront; an old generation is a re-entry refusal. */
    public synchronized boolean pistonMove(long generation) {
        if (generation != wavefrontGen) {
            reentries++;
            return false;
        }
        pistonMoves++;
        return true;
    }

    /** The witness of one position: every transition kept, even when the end equals the start. */
    public synchronized List<Transition> witness(long blockPos) {
        List<Transition> kept = new ArrayList<>();
        for (Transition transition : transitions) {
            if (transition.blockPos() == blockPos) {
                kept.add(transition);
            }
        }
        return List.copyOf(kept);
    }

    /** The number of transitions of one position; a folded short pulse is a causality violation. */
    public synchronized int edges(long blockPos) {
        return witness(blockPos).size();
    }

    public synchronized List<Notification> notifications() {
        return List.copyOf(notifications);
    }

    public synchronized boolean sent(String notificationId) {
        return sent.contains(notificationId);
    }

    public synchronized long duplicates() {
        return duplicates;
    }

    public synchronized long stale() {
        return stale;
    }

    public synchronized long pistonMoves() {
        return pistonMoves;
    }

    public synchronized long reentries() {
        return reentries;
    }
}
