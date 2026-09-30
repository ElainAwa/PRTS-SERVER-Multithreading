/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.meter.MeterWindow;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The module entry: one instance per process, driven once per tick by the platform listener.
 *
 * <p>The driver is read-only with respect to the world. It reclaims expired tokens, plans the time
 * budget, turns overruns into records that are never executed, checks the accounting closure and
 * publishes the metering window when its length has passed. It occupies no world write path and no
 * lock, and the platform subscriber exists only while the kernel category is on.</p>
 *
 * <p>The only clock read here is the one that measures the driver itself, so the cost of the
 * observation can be published as a row of its own. Planning reads the tick index and the metered
 * work, never the clock.</p>
 */
public final class KernelModule {

    /** The control plane of one tick: the five values the next tick would plan with. */
    public record ControlFrame(long tickIndex, double minMarginMs, long overrunHits,
                               long waitBoundHits, double reserveUsedMs, double reserveRemainingMs,
                               String degradeState) {

        /** @return a frame for a tick the driver did not reach */
        public static ControlFrame empty() {
            return new ControlFrame(0L, 0.0, 0L, 0L, 0.0, 0.0, "none");
        }
    }

    private static final KernelModule INSTANCE = new KernelModule();
    private static final String RUNTIME_WORLD = "";

    private final OwnerRegistry owners = new OwnerRegistry();
    private final IntentQueue intents = new IntentQueue(KernelSettings::intentQueueCap);
    private final WriteLedger ledger = new WriteLedger();
    private final WriteAuthority authority = new WriteAuthority(owners, intents, ledger,
        KernelSettings::enforceUnregisteredWrites, KernelSettings::retryBudget);
    private final WaitPointRegistry waitPoints = new WaitPointRegistry(KernelSettings::waitBoundMs);
    private final SharePlanner shares = new SharePlanner();

    private long tickIndex;
    private long windowStartTick;
    private boolean started;
    private MeterWindow lastWindow;
    private ConservationCheck lastConservation = ConservationCheck.holds();
    private ControlFrame control = ControlFrame.empty();

    private KernelModule() {
    }

    /** @return the module of this process */
    public static KernelModule instance() {
        return INSTANCE;
    }

    /**
     * Advances the kernel by one tick.
     *
     * @param worldIds the worlds the tick carries, in the order the platform lists them
     */
    public void serverTick(List<String> worldIds) {
        if (!KernelSettings.enabled()) {
            return;
        }
        long startedAt = System.nanoTime();
        tickIndex++;
        if (!started) {
            started = true;
            windowStartTick = tickIndex;
        }
        if (KernelSettings.selfTimers()) {
            owners.reclaimExpired(tickIndex);
        }
        if (KernelSettings.shareTable()) {
            planBudget(worldIds);
        }
        if (KernelSettings.selfTimers()) {
            publishWindowIfDue();
        }
        ledger.verifyClosure();
        SelfTimers.note(SelfClass.OBSERVE, RUNTIME_WORLD, "runtime", System.nanoTime() - startedAt);
    }

    private void planBudget(List<String> worldIds) {
        Map<String, EnumMap<ShareClass, Double>> used = KernelSettings.selfTimers()
            ? SharePlanner.usedFromTickTotals(SelfTimers.consumeTickTotals())
            : Map.of();
        ShareTable table = shares.plan(worldIds == null ? List.of() : worldIds, tickIndex, used);
        lastConservation = shares.checkConservation(table);
        double minMargin = Double.POSITIVE_INFINITY;
        long overruns = 0L;
        for (ShareTable.ShareRow row : table.rows()) {
            minMargin = Math.min(minMargin, row.marginMs());
            if (!row.overrun()) {
                continue;
            }
            OverrunRecord record = shares.record(row, "runtime", tickIndex);
            shares.recordWouldDegrade(record);
            overruns++;
        }
        if (minMargin == Double.POSITIVE_INFINITY) {
            minMargin = 0.0;
        }
        control = new ControlFrame(tickIndex, minMargin, overruns, waitPoints.waitOverrunCount(),
            reserveUsedMs(), shares.lastTable().reserve().remainingMs(), "none");
    }

    private void publishWindowIfDue() {
        long length = KernelSettings.selfWindowTicks();
        long covered = tickIndex - windowStartTick;
        if (covered >= length) {
            lastWindow = SelfTimers.consume(tickIndex, (int) Math.min(Integer.MAX_VALUE, covered),
                tickIndex <= KernelSettings.selfWarmupTicks());
            windowStartTick = tickIndex;
        }
    }

    private double reserveUsedMs() {
        double used = 0.0;
        for (double value : shares.reserveUsedByPurpose().values()) {
            used += value;
        }
        return used;
    }

    /**
     * Returns the window to publish.
     *
     * @return the last completed window, or a live view when none has completed yet
     */
    public MeterWindow window() {
        if (lastWindow != null) {
            return lastWindow;
        }
        long covered = started ? tickIndex - windowStartTick : 0L;
        return SelfTimers.snapshot(tickIndex, (int) Math.min(Integer.MAX_VALUE, covered),
            tickIndex <= KernelSettings.selfWarmupTicks());
    }

    /** @return the tick index the module reached */
    public long tickIndex() {
        return tickIndex;
    }

    /** @return whether a tick was driven at all */
    public boolean started() {
        return started;
    }

    /** @return the owner registry */
    public OwnerRegistry owners() {
        return owners;
    }

    /** @return the intent queue */
    public IntentQueue intents() {
        return intents;
    }

    /** @return the write ledger */
    public WriteLedger ledger() {
        return ledger;
    }

    /** @return the write decision point */
    public WriteAuthority authority() {
        return authority;
    }

    /** @return the wait point registry */
    public WaitPointRegistry waitPoints() {
        return waitPoints;
    }

    /** @return the share planner */
    public SharePlanner shares() {
        return shares;
    }

    /** @return the conservation of the last planned table */
    public ConservationCheck conservation() {
        return lastConservation;
    }

    /** @return the control plane of the last tick */
    public ControlFrame control() {
        return control;
    }

    /** Clears the live counters. Used by the readout reset and by tests, never by the scheduler. */
    public void resetReadings() {
        SelfTimers.resetAll();
        tickIndex = 0L;
        windowStartTick = 0L;
        started = false;
        lastWindow = null;
        lastConservation = ConservationCheck.holds();
        control = ControlFrame.empty();
    }
}
