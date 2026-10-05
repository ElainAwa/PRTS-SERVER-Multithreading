/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers.MeterWindow;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.observe.WaitSiteObserver;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The driver reclaims expired tokens, plans the time budget, turns overruns into records that are
 * never executed, checks the accounting closure and publishes the metering window when its length
 * has passed. */
public final class KernelModule {

    /** The control plane of one tick: the five values the next tick would plan with. */
    public record ControlFrame(long tickIndex, double minMarginMs, long overrunHits,
                               long waitBoundHits, double reserveUsedMs, double reserveRemainingMs,
                               String degradeState) {

        public static ControlFrame empty() {
            return new ControlFrame(0L, 0.0, 0L, 0L, 0.0, 0.0, "none");
        }
    }

    private static final KernelModule INSTANCE = new KernelModule();
    private static final String RUNTIME_WORLD = "";
    private static final String SERVER_SITE = "host:server-thread";

    private final OwnerRegistry owners = new OwnerRegistry();
    private final IntentQueue intents = new IntentQueue(KernelSettings::intentQueueCap,
        KernelSettings::retryBudget);
    private final CommitSegment commitSegment = new CommitSegment(intents,
        KernelModule::commitWanted, KernelSettings::commitBudget);
    private final WriteLedger ledger = new WriteLedger();
    private final WritePathCounters pathCounters = new WritePathCounters();
    private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
    private final WriteAuthority authority = new WriteAuthority(owners, intents, ledger,
        KernelSettings::enforceUnregisteredWrites, KernelSettings::retryBudget);
    private final WorldWriteGuard guard = new WorldWriteGuard(pathCounters, authority, intents,
        payloads, ledger);
    private final WaitPointRegistry waitPoints = new WaitPointRegistry(KernelSettings::waitBoundMs,
        KernelSettings::refuseUnregisteredWaits);
    private final WaitSiteObserver waitSites = new WaitSiteObserver(waitPoints, this::tickIndex,
        KernelSettings::waitBoundMs);
    private final SharePlanner shares = new SharePlanner();

    private final ArenaLedger arena = new ArenaLedger();
    private final List<KernelDomain> domains = new ArrayList<>();
    private long tickIndex;
    private boolean waitSiteTapInstalled;
    private long windowStartTick;
    private boolean started;
    private MeterWindow lastWindow;
    private ConservationCheck lastConservation = ConservationCheck.holds();
    private ControlFrame control = ControlFrame.empty();

    private KernelModule() {
        intents.bindPayload(guard);
        // The progress side of the two rows whose producer already exists: the intent channel depth
        // and the world epoch change count are the same counters the readout publishes, so a signal
        // reading and its control-plane value can never disagree.
        waitPoints.progress().bind("xdomain", "intent.queue_depth", intents::depth);
        waitPoints.progress().bind("worldlife", "write.world_epochs_changes",
            () -> guard.worldEpochs().epochChanges());
    }

    public static KernelModule instance() {
        return INSTANCE;
    }

    /** Advances the kernel by one tick. */
    public void serverTick(List<String> worldIds) {
        if (!KernelSettings.enabled()) {
            guard.refresh(false, false, false, tickIndex);
            syncWaitSiteTap(false);
            shutdownDomains();
            return;
        }
        long startedAt = System.nanoTime();
        tickIndex++;
        if (!started) {
            started = true;
            windowStartTick = tickIndex;
        }
        commitSegment.bindOwnerThread(Thread.currentThread());
        if (!guard.serverThreadBound()) {
            guard.bindServerThread(Thread.currentThread(), SERVER_SITE);
        }
        guard.noteLiveWorlds(worldIds);
        guard.refresh(KernelSettings.writePathGuard(),
            KernelSettings.enforceUnregisteredWrites(),
            KernelSettings.routeUnregisteredWrites(), tickIndex);
        syncWaitSiteTap(KernelSettings.waitRegistry());
        commitSegment.run(tickIndex);
        owners.reclaimExpired(tickIndex);
        if (KernelSettings.shareTable()) {
            planBudget(worldIds);
        } else {
            SelfTimers.discardTickTotals();
        }
        if (KernelSettings.selfTimers()) {
            publishWindowIfDue();
        }
        ledger.verifyClosure();
        tickDomains();
        waitPoints.noteTick();
        if (KernelSettings.selfTimers()) {
            SelfTimers.note(SelfClass.OBSERVE, RUNTIME_WORLD, "runtime",
                System.nanoTime() - startedAt);
        }
    }

    /** Installs the write path watcher. Called while the category is on. */
    public void installWritePathTap() {
        PrtsWorldWriteTaps.install(guard);
    }

    /** Removes the write path watcher. */
    public void removeWritePathTap() {
        PrtsWorldWriteTaps.install(null);
    }

    /** Installs the wait observation watcher. Called while the category is on. */
    public void installWaitSiteTap() {
        syncWaitSiteTap(KernelSettings.waitRegistry());
    }

    /** Removes the wait observation watcher. */
    public void removeWaitSiteTap() {
        syncWaitSiteTap(false);
    }

    /** A tool that borrows the seam - the self check is one - hands it back here instead of
     * leaving the process with whatever it installed last: the module forgets what it believed was
     * installed and installs the configured watcher again, so a borrowed seam cannot silently end
     * the observation of the twenty real call sites. */
    public synchronized void resyncWaitSiteTap() {
        // What is installed now is read first: a tool that handed the seam back leaves the module
        // agreeing with it instead of installing its own watcher over a seam somebody else owns.
        waitSiteTapInstalled = PrtsWaitSites.watcher() == waitSites;
        syncWaitSiteTap(KernelSettings.enabled() && KernelSettings.waitRegistry());
    }

    private void syncWaitSiteTap(boolean wanted) {
        if (waitSiteTapInstalled == wanted) {
            return;
        }
        waitSiteTapInstalled = wanted;
        PrtsWaitSites.install(wanted ? waitSites : null);
    }

    private static boolean commitWanted() {
        return KernelSettings.commitIntents() || KernelSettings.dispatchTakeover();
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

    /** The arena the domains share with the module. */
    public ArenaLedger arena() {
        return arena;
    }

    /** The directory the intent payloads of the write path are bound in. */
    public IntentPayloadDirectory payloads() {
        return payloads;
    }

    /** Installs one domain; installing the same identity again replaces the previous instance. */
    public void installDomain(KernelDomain domain) {
        domains.removeIf(existing -> existing.id().equals(domain.id()));
        domains.add(domain);
    }

    /** The domains this module drives, in installation order. */
    public List<KernelDomain> domains() {
        return List.copyOf(domains);
    }

    private void tickDomains() {
        for (KernelDomain domain : domains) {
            domain.tick(tickIndex);
        }
    }

    private void shutdownDomains() {
        for (KernelDomain domain : domains) {
            domain.shutdown();
        }
    }

    public MeterWindow window() {
        if (lastWindow != null) {
            return lastWindow;
        }
        long covered = started ? tickIndex - windowStartTick : 0L;
        return SelfTimers.snapshot(tickIndex, (int) Math.min(Integer.MAX_VALUE, covered),
            tickIndex <= KernelSettings.selfWarmupTicks());
    }

    public long tickIndex() {
        return tickIndex;
    }

    public boolean started() {
        return started;
    }

    public OwnerRegistry owners() {
        return owners;
    }

    public IntentQueue intents() {
        return intents;
    }

    public CommitSegment commitSegment() {
        return commitSegment;
    }

    public WriteLedger ledger() {
        return ledger;
    }

    public WriteAuthority authority() {
        return authority;
    }

    public WorldWriteGuard guard() {
        return guard;
    }

    public WaitPointRegistry waitPoints() {
        return waitPoints;
    }

    public WaitSiteObserver waitSites() {
        return waitSites;
    }

    public SharePlanner shares() {
        return shares;
    }

    public ConservationCheck conservation() {
        return lastConservation;
    }

    public ControlFrame control() {
        return control;
    }

    /** Clears the live counters. */
    public void resetReadings() {
        for (KernelDomain domain : domains) {
            domain.reset();
        }
        arena.reset();
        SelfTimers.resetAll();
        guard.resetReadings();
        waitSites.reset();
        waitPoints.resetReadings();
        commitSegment.reset();
        tickIndex = 0L;
        windowStartTick = 0L;
        started = false;
        lastWindow = null;
        lastConservation = ConservationCheck.holds();
        control = ControlFrame.empty();
    }
}
