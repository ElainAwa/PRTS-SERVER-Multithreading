/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.region;

import java.util.Objects;

/** The four-step world unload pipeline: stop new work, drain the in-flight obligations and settle
 * their faults boundedly, verify the requested save watermark, then advance the incarnation and
 * reclaim the owners, arena slots and region entries. A step that fails stays at its own state with
 * every holder and unfinished account kept; GONE is reached only when all four steps succeeded and
 * no evidence is ever cleared with a map clear. */
public final class WorldUnloadPipeline {

    /** The exact terminal state of one unload attempt. */
    public enum Step {
        UNLOADING,
        DRAINED,
        FAULT_SETTLED,
        SAVE_VERIFIED,
        RECLAIMED,
        GONE,
        FAILED
    }

    /** The bounded settle result of one world. */
    public record Settle(int settled, int remaining, boolean complete) {
    }

    private final String worldId;
    private long incarnation;
    private Step step = Step.UNLOADING;
    private int inFlight;
    private int unrecorded;
    private int owners;
    private int arenaSlots;
    private long saveLevel;
    private long requestedSaveLevel;
    private long faultsSettled;
    private boolean gone;

    public WorldUnloadPipeline(String worldId, long incarnation) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.incarnation = incarnation;
    }

    /** Holds one in-flight obligation that step two must drain. */
    public synchronized void holdInFlight() {
        if (step == Step.UNLOADING) {
            inFlight++;
        }
    }

    /** Holds one unrecorded ticket that step two must explicitly settle. */
    public synchronized void holdUnrecorded(int count) {
        if (step == Step.UNLOADING) {
            unrecorded += count;
        }
    }

    /** Holds one owner token and one arena slot the reclaim step must retire. */
    public synchronized void holdOwnerAndSlot() {
        if (step == Step.UNLOADING) {
            owners++;
            arenaSlots++;
        }
    }

    public synchronized void requireSaveLevel(long level) {
        requestedSaveLevel = Math.max(requestedSaveLevel, level);
    }

    /** Step one: the world stops accepting new work; it stays UNLOADING until it drains. */
    public synchronized void stopNewWork() {
        if (gone) {
            return;
        }
        step = Step.UNLOADING;
    }

    /** Step two: drains in-flight obligations boundedly and settles unrecorded faults explicitly. */
    public synchronized Settle drainAndSettle(int maxIterations) {
        if (gone) {
            return new Settle(0, inFlight, true);
        }
        int budget = Math.max(0, maxIterations);
        while (budget > 0 && (inFlight > 0 || unrecorded > 0)) {
            if (inFlight > 0) {
                inFlight--;
                faultsSettled++;
            } else {
                unrecorded--;
                faultsSettled++;
            }
            budget--;
        }
        boolean complete = inFlight == 0 && unrecorded == 0;
        step = complete ? Step.DRAINED : Step.UNLOADING;
        return new Settle((int) faultsSettled, inFlight + unrecorded, complete);
    }

    /** Step three: the requested save watermark is verified; a short level keeps UNLOADING. */
    public synchronized boolean verifySave(long level) {
        if (inFlight > 0 || unrecorded > 0) {
            step = Step.UNLOADING;
            return false;
        }
        saveLevel = level;
        boolean verified = level >= requestedSaveLevel;
        step = verified ? Step.SAVE_VERIFIED : Step.UNLOADING;
        return verified;
    }

    /** Step four: only a verified unload advances the incarnation and reclaims every right. */
    public synchronized boolean reclaim() {
        if (step != Step.SAVE_VERIFIED) {
            return false;
        }
        incarnation++;
        owners = 0;
        arenaSlots = 0;
        step = Step.GONE;
        gone = true;
        return true;
    }

    public synchronized Step step() {
        return step;
    }

    public synchronized long incarnation() {
        return incarnation;
    }

    public synchronized int owners() {
        return owners;
    }

    public synchronized int arenaSlots() {
        return arenaSlots;
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    public synchronized boolean gone() {
        return gone;
    }

    public synchronized long faultsSettled() {
        return faultsSettled;
    }

    public synchronized String worldId() {
        return worldId;
    }
}
