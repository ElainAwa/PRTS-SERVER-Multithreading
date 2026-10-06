/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers.MeterWindow;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.exits.DualExits;
import io.izzel.arclight.common.prts.kernel.safety.SafetyNet;
import io.izzel.arclight.common.prts.kernel.shares.BudgetStateMachine;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.waitpoints.CoverageReport;
import io.izzel.arclight.common.prts.kernel.waitpoints.ForcedConvergence;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.support.PrtsSeams;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The compact kernel section the status command shows. The section is a summary, not a second
 * source of numbers: every value here is the same value the full export publishes, so a reader can
 * move between the two without meeting a different count. */
public final class KernelStatusLines {

    private KernelStatusLines() {
    }

    /** How many of the closed set of kinds the net saw at least once. A kind that was never seen is
     * still published with its zero, so this is a summary and not the census. */
    private static int seenKinds(SafetyNet net) {
        int seen = 0;
        for (SafetyNet.KindCounts counts : net.kinds()) {
            if (counts.total() > 0L) {
                seen++;
            }
        }
        return seen;
    }

    /** Renders the status section. */
    public static List<String> status(KernelModule module) {
        List<String> lines = new ArrayList<>();
        lines.add("[PRTS] kernel: category=" + KernelSettings.enabled()
            + " enforce-unregistered-writes=" + KernelSettings.enforceUnregisteredWrites()
            + " self-timers=" + KernelSettings.selfTimers()
            + " share-table=" + KernelSettings.shareTable()
            + " wait-registry=" + KernelSettings.waitRegistry()
            + " write-path-guard=" + KernelSettings.writePathGuard()
            + " commit-intents=" + KernelSettings.commitIntents()
            + " route-unregistered-writes=" + KernelSettings.routeUnregisteredWrites()
            + " write-controlled-slot=" + KernelSettings.writeControlledSlot());
        List<PrtsSeams.SeamState> seams = PrtsSeams.states(KernelSettings::categoryEnabled);
        int reachable = 0;
        int applied = 0;
        int decidedOff = 0;
        List<String> gaps = new ArrayList<>();
        List<String> gapCategories = new ArrayList<>();
        for (PrtsSeams.SeamState state : seams) {
            if (state.reachable()) {
                reachable++;
            } else {
                gaps.add(state.seam().seamId());
                if (!gapCategories.contains(state.seam().category())) {
                    gapCategories.add(state.seam().category());
                }
            }
            if (state.applied()) {
                applied++;
            }
            if (state.decisionKnown() && !state.decidedToApply()) {
                decidedOff++;
            }
        }
        boolean judgeable = KernelSettings.enabled() && gaps.isEmpty();
        lines.add("[PRTS] kernel: seams declared=" + seams.size()
            + " reachable=" + reachable + " applied=" + applied + " refused=" + decidedOff
            + " gaps=" + gaps.size()
            + " write_path_tap=" + (PrtsWorldWriteTaps.installed() ? 1 : 0)
            + " wait_watcher=" + (PrtsWaitSites.installed() ? 1 : 0)
            + " judgeable=" + (judgeable ? 1 : 0));
        if (!gaps.isEmpty()) {
            lines.add("[PRTS] kernel: seam gap " + String.join(",", gaps)
                + " - category " + String.join(",", gapCategories)
                + " is off, so the real call sites do not reach the kernel:"
                + " the kernel readings are not evidence until that category is on");
        } else if (!KernelSettings.enabled() && applied > 0) {
            lines.add("[PRTS] kernel: " + applied + " call-site seams are applied while the kernel"
                + " category is off: every call site pays one volatile read and reaches no kernel");
        }
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
        WriteControlledSlots controlled = module.controlledSlots();
        lines.add("[PRTS] kernel: controlled slot enabled=" + (controlled.enabled() ? 1 : 0)
            + " slots=" + controlled.slots()
            + " registered=" + controlled.registeredCount()
            + " refused=" + controlled.refusedCount()
            + " attempts=" + controlled.attemptsCount()
            + " default_path=" + controlled.defaultPathCount()
            + " captured=" + controlled.capturedCount()
            + " rejected=" + controlled.rejectedCount()
            + " not_executable=" + controlled.notExecutableCount()
            + " conservation=" + (controlled.conservationHolds() ? "ok" : "broken")
            + " landed=" + module.commits().sequence());
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
        ConservationCheck conservation = module.conservation();
        BudgetStateMachine.Decision state = module.budgetState();
        DegradeLadder ladder = module.ladder();
        lines.add("[PRTS] kernel: budget state="
            + (state == null ? "unplanned" : BudgetStateMachine.key(state.phase()))
            + " reason=" + (state == null ? "unplanned" : state.reason())
            + " conservation=" + conservation.verdict().name().toLowerCase(Locale.ROOT)
            + " planned=" + KernelReadings.format(conservation.plannedMs())
            + "/" + KernelReadings.format(conservation.eBudgetMs())
            + " ladder=" + ladder.sign().deepest().name().toLowerCase(Locale.ROOT)
            + " rungs=" + ladder.rungs().size()
            + " entered=" + ladder.enteredTotal()
            + " effective=" + ladder.effectiveTotal()
            + " rollback_ticks=" + KernelSettings.degradeRollbackTicks()
            + " observation_only=1");
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
        lines.add("[PRTS] kernel: wait observed=" + module.waitPoints().observationCount()
            + " max_ms=" + module.waitPoints().maxWaitMs()
            + " over_one_tick=" + module.waitSites().readings().overOneTickTotal()
            + " convergence_candidates="
            + module.waitSites().readings().convergenceCandidateTotal()
            + " watcher=" + (PrtsWaitSites.installed() ? 1 : 0));
        WaitPointRegistry.NineRows nine = module.waitPoints().nineRows();
        ForcedConvergence.Rollback rollback = module.waitPoints().convergence()
            .rollback(ForcedConvergence.ROLLBACK_WINDOW_TICKS);
        lines.add("[PRTS] kernel: wait contract rows=" + nine.rows() + "/" + nine.contractRows()
            + " aligned=" + (nine.aligned() ? 1 : 0)
            + " signals_bound=" + module.waitPoints().progress().boundCount() + "/"
            + module.waitPoints().progress().declaredCount()
            + " bound_ms=" + module.waitPoints().boundMs()
            + " refuse_unregistered=" + (KernelSettings.refuseUnregisteredWaits() ? 1 : 0)
            + " refused=" + module.waitPoints().refusedUnregistered()
            + " convergence_reached=" + module.waitPoints().convergence().reached()
            + " convergence_effective=" + module.waitPoints().convergence().effective()
            + " rollback_ready=" + (rollback.ready() ? 1 : 0)
            + " observation_only=1");
        TickPlan plan = module.plans().latest();
        lines.add("[PRTS] kernel: plan built=" + module.plans().plansBuilt()
            + " failures=" + module.plans().failures()
            + " rate=" + KernelReadings.format(module.plans().failureRate())
            + " rebuilds=" + module.plans().rebuilds()
            + " unknown_sites=" + module.plans().unknownSites()
            + " nodes=" + (plan == null ? 0 : plan.graph().nodeCount())
            + " steps=" + (plan == null ? 0 : plan.commitOrder().size())
            + " sequence=" + (plan == null ? 0L : plan.planSequence())
            + " observation_only=1");
        lines.add("[PRTS] kernel: jobs dispatched=" + module.scheduler().dispatched()
            + " settled=" + module.scheduler().settledTotal()
            + " backpressure=" + module.scheduler().backpressureHits()
            + " cancelled=" + module.scheduler().cancelledTotal()
            + " timed_out=" + module.scheduler().timedOutTotal()
            + " intake=" + module.jobIntake().depth() + "/" + module.jobIntake().capacity()
            + " commit entries=" + module.commits().sequence()
            + " accepted=" + module.commits().accepted()
            + " dropped=" + module.commits().dropped()
            + " order_violations=" + module.commits().orderViolations()
            + " rings=" + module.commits().ringCount()
            + " observation_only=1");
        SafetyNet net = module.safety();
        DualExits exits = module.exits();
        DualExits.SameSource same = exits.sameSource();
        lines.add("[PRTS] kernel: safety kinds=" + SafetyNet.ViolationKind.kindCount()
            + " violations=" + net.total()
            + " kinds_seen=" + seenKinds(net)
            + " escalated=" + net.escalated()
            + " escalation_switch=" + (net.switchEnabled() ? 1 : 0)
            + " cascade_cap=" + net.cascade().cap()
            + " cascade_capped=" + net.cascade().capped()
            + " zero_effect=" + module.zeroEffect().zeroEffectTotal()
            + " zero_effect_unproven=" + module.zeroEffect().unprovenTotal()
            + " evidence_empty=" + net.evidenceEmpty()
            + " observation_only=1");
        lines.add("[PRTS] kernel: exits control_frames=" + exits.controlFrames()
            + " judgement_frames=" + exits.judgementFrames()
            + " window_ticks=" + exits.windowTicks()
            + " same_source=" + same.sameValue() + "/" + same.fields()
            + " equal=" + (same.equal() ? 1 : 0)
            + " window_feed_refused=" + exits.controlWindowFeeds()
            + " judgement_write_deps=" + exits.judgementWriteDependencies()
            + " groups=" + (exits.groups().getOrDefault("a", false) ? 1 : 0)
            + "/" + (exits.groups().getOrDefault("b", false) ? 1 : 0)
            + "/" + (exits.groups().getOrDefault("shared", false) ? 1 : 0)
            + " observation_only=1");
        lines.add("[PRTS] kernel: observation requests, not an approved counter table;"
            + " run '/prts kernel' for the full export");
        return lines;
    }
}
