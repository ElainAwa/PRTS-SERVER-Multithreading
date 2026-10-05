/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes the plans of the recent ticks and answers the two questions the layers around the
 * planning period ask: what the next tick plans with, and which order a commit that was reached now
 * belongs to. The history is bounded: an order that fell out of it is answered as unknown. */
public final class TickPlanStore {

    /** The plan and the step that ordered one commit. */
    public record StepRef(long planSequence, int position, boolean intent, String nodeKey) {
    }

    /** The control plane of one tick as the next planning period consumes it: the share margin left,
     * the two hit counts, the reserve that remains, the degradation state and the identity of the
     * world set it was taken on. A frame that was never taken is missing rather than zero. */
    public record Control(boolean observed, long tickIndex, long planSequence,
                          long worldSetGeneration, double minMarginMs, long overrunHits,
                          long waitBoundHits, double reserveRemainingMs, String degradeState) {

        public static Control missing() {
            return new Control(false, 0L, 0L, 0L, 0.0, 0L, 0L, 0.0, "unplanned");
        }

        public static Control of(long tickIndex, long planSequence, long worldSetGeneration,
                                 double minMarginMs, long overrunHits, long waitBoundHits,
                                 double reserveRemainingMs, String degradeState) {
            return new Control(true, tickIndex, planSequence, worldSetGeneration, minMarginMs,
                overrunHits, waitBoundHits, reserveRemainingMs,
                degradeState == null ? "none" : degradeState);
        }
    }

    private final int historyCap;
    private final Map<Long, TickPlan> history = new LinkedHashMap<>();
    private Control control = Control.missing();
    private TickPlan latest;
    private long built;
    private long failures;
    private long rebuilds;
    private long bootstrapSkips;
    private long unknownSites;
    private long unresolved;
    private RejectCode lastFailure;

    public TickPlanStore(int historyCap) {
        this.historyCap = Math.max(1, historyCap);
    }

    /** Publishes one plan; a world set that differs from the previous plan is counted as a rebuild. */
    public void publish(TickPlan plan) {
        if (plan == null) {
            return;
        }
        if (latest != null && latest.worldSetGeneration() != plan.worldSetGeneration()) {
            rebuilds++;
        }
        latest = plan;
        built++;
        unknownSites += plan.unknownSites();
        history.put(plan.planSequence(), plan);
        while (history.size() > historyCap) {
            Long oldest = history.keySet().iterator().next();
            history.remove(oldest);
        }
    }

    /** Publishes the frame the next tick consumes. It is published once per tick whatever the plan
     * build did, because a refused build must not leave the next one without an input. */
    public void noteControl(Control frame) {
        if (frame != null) {
            control = frame;
        }
    }

    /** Records a build the planning period refused. */
    public void noteFailure(RejectCode code) {
        failures++;
        lastFailure = code;
    }

    /** Records a tick without a control frame to plan from. */
    public void noteBootstrapSkip() {
        bootstrapSkips++;
    }

    public TickPlan latest() {
        return latest;
    }

    public TickPlan planOf(long planSequence) {
        return history.get(planSequence);
    }

    public List<TickPlan> recent() {
        return new ArrayList<>(history.values());
    }

    /** The frame the next tick plans with. */
    public Control control() {
        return control;
    }

    /** Resolves the order of one commit by the key its job declared, newest plan first. */
    public StepRef resolve(String worldId, String domainId, String nodeKey) {
        List<TickPlan> plans = new ArrayList<>(history.values());
        for (int index = plans.size() - 1; index >= 0; index--) {
            TickPlan plan = plans.get(index);
            for (TickPlan.CommitStep step : plan.commitOrder()) {
                if (step.worldId().equals(worldId) && step.domainId().equals(domainId)
                    && step.nodeKey().equals(nodeKey)) {
                    return new StepRef(plan.planSequence(), step.position(), step.intent(),
                        step.nodeKey());
                }
            }
        }
        unresolved++;
        return null;
    }

    public int historySize() {
        return history.size();
    }

    public int historyCap() {
        return historyCap;
    }

    public long plansBuilt() {
        return built;
    }

    public long failures() {
        return failures;
    }

    public long rebuilds() {
        return rebuilds;
    }

    public long bootstrapSkips() {
        return bootstrapSkips;
    }

    public long unknownSites() {
        return unknownSites;
    }

    public long unresolvedLookups() {
        return unresolved;
    }

    public RejectCode lastFailure() {
        return lastFailure;
    }

    /** The share of ticks whose plan build was refused. It is a reading: no decision reads it. */
    public double failureRate() {
        long attempts = built + failures + bootstrapSkips;
        return attempts == 0L ? 0.0 : (double) failures / (double) attempts;
    }

    public void reset() {
        history.clear();
        control = Control.missing();
        latest = null;
        built = 0L;
        failures = 0L;
        rebuilds = 0L;
        bootstrapSkips = 0L;
        unknownSites = 0L;
        unresolved = 0L;
        lastFailure = null;
    }
}
