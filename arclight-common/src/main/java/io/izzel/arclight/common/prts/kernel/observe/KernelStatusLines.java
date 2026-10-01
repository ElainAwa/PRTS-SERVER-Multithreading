/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.meter.MeterWindow;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.waitpoints.CoverageReport;

import java.util.ArrayList;
import java.util.List;

/**
 * The compact kernel section the status command shows.
 *
 * <p>The section is a summary, not a second source of numbers: every value here is the same value
 * the full export publishes, so a reader can move between the two without meeting a different
 * count. The last line states that the fields are observation requests rather than an approved
 * counter table.</p>
 *
 * <p>The write path lines report what the real write paths did: how many attempts each of them
 * carried, how they left, and whether the per-path accounting closes. The intent line reports the
 * channel the same way: what was frozen into it, what the commit segment applied, and the mode the
 * segment is in - {@code hold} means nothing is consumed and the depth is what is waiting.</p>
 */
public final class KernelStatusLines {

    private KernelStatusLines() {
    }

    /**
     * Renders the status section.
     *
     * @param module the module to read
     * @return the section lines, already prefixed
     */
    public static List<String> status(KernelModule module) {
        List<String> lines = new ArrayList<>();
        lines.add("[PRTS] kernel: category=" + KernelSettings.enabled()
            + " enforce-unregistered-writes=" + KernelSettings.enforceUnregisteredWrites()
            + " self-timers=" + KernelSettings.selfTimers()
            + " share-table=" + KernelSettings.shareTable()
            + " wait-registry=" + KernelSettings.waitRegistry()
            + " write-path-guard=" + KernelSettings.writePathGuard()
            + " commit-intents=" + KernelSettings.commitIntents()
            + " route-unregistered-writes=" + KernelSettings.routeUnregisteredWrites());
        WriteLedger ledger = module.ledger();
        lines.add("[PRTS] kernel: write attempts=" + ledger.totalAttempts()
            + " granted=" + ledger.totalGranted() + " intent=" + ledger.totalIntent()
            + " denied=" + ledger.totalDenied()
            + " closure=" + (ledger.accountingOk() ? "ok" : "broken")
            + " unregistered_grant=" + ledger.unregisteredGrants());
        WritePathCounters paths = module.guard().counters();
        lines.add("[PRTS] kernel: write paths attempts=" + paths.totalAttempts()
            + " granted=" + paths.total(WriteDisposition.GRANT)
            + " intent=" + paths.total(WriteDisposition.INTENT)
            + " denied=" + paths.total(WriteDisposition.DENY)
            + " unregistered=" + paths.unregisteredAttempts()
            + " closure=" + (paths.closureHolds() ? "ok" : "broken")
            + " guard_active=" + (module.guard().active() ? 1 : 0)
            + " enforce=" + (module.guard().enforcing() ? 1 : 0)
            + " routing=" + (module.guard().routing() ? 1 : 0));
        lines.add("[PRTS] kernel: intent depth=" + module.intents().depth() + "/"
            + module.intents().capacity() + " enqueued=" + module.intents().enqueuedCount()
            + " committed=" + module.intents().committedCount()
            + " executed=" + module.intents().executedCount()
            + " mode=" + module.commitSegment().mode()
            + " last_exec_tick=" + module.intents().lastExecTick()
            + " order_violations=" + module.intents().orderViolationCount());
        MeterWindow window = module.window();
        lines.add("[PRTS] kernel: self rows=" + window.rowCount()
            + " missing=" + window.missingClasses() + " sample_rate="
            + KernelReadings.format(window.sampleRate())
            + " lost=" + window.lostSamples() + " observe_ms="
            + KernelReadings.format(window.observeMs())
            + " window_ticks=" + window.windowTicks());
        ShareTable table = module.shares().lastTable();
        if (table == null) {
            lines.add("[PRTS] kernel: budget rows=0 conservation=unplanned reserve="
                + KernelReadings.format(KernelSettings.reserveMs()));
        } else {
            lines.add("[PRTS] kernel: budget rows=" + table.rows().size()
                + " conservation=" + (module.conservation().ok() ? "ok" : "over")
                + " overrun_class=" + module.shares().classOverrunTotal()
                + " overrun_world=" + module.shares().worldOverrunTotal()
                + " reserve=" + KernelReadings.format(table.reserve().reserveMs()) + "/"
                + KernelReadings.format(table.reserve().usedMs()));
        }
        CoverageReport coverage = module.waitPoints().reportCoverage();
        lines.add("[PRTS] kernel: wait points registered=" + coverage.registeredTotal()
            + " unregistered=" + coverage.unregistered()
            + " coverage=" + KernelReadings.format(coverage.coveragePct()) + "%"
            + " forced_convergence=" + coverage.forcedConvergence());
        lines.add("[PRTS] kernel: wait sites listed=" + coverage.siteInventoryTotal()
            + " registered=" + coverage.siteRegistered()
            + " unregistered=" + coverage.siteUnregistered()
            + " uncovered=" + coverage.siteUncoveredIds().size()
            + " coverage=" + KernelReadings.format(coverage.siteCoveragePct()) + "%"
            + " call_sites=" + module.waitPoints().sites().callSites());
        lines.add("[PRTS] kernel: observation requests, not an approved counter table;"
            + " run '/prts kernel' for the full export");
        return lines;
    }
}
