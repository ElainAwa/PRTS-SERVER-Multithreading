/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.accept;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The three-ledger reconciler: one business identity joins its business ledger (input and one
 * mutually exclusive terminal/bucket state), its execution/effect ledger (an applied witness and the
 * target exit watermark) and its resource ledger (held owner tokens, arena slots and queue slots).
 * A DONE state without its effect witness, a terminal state that still holds a resource, or a fault
 * that retains no obligation is a fake green and is reported, never counted as complete. */
public final class ThreeLedgerReconciler {

    /** The unified identity of one business unit; every field is required so a blank identity can
     * never stand in for a legal no-op. */
    public record Identity(String worldIncarnationKey, long lifecycleGeneration,
                           long logicalUpdateOrdinal, String businessKind) {

        public Identity {
            Objects.requireNonNull(worldIncarnationKey, "worldIncarnationKey");
            Objects.requireNonNull(businessKind, "businessKind");
            if (worldIncarnationKey.isEmpty() || businessKind.isEmpty()) {
                throw new IllegalArgumentException("a business identity names its world and kind");
            }
            if (lifecycleGeneration < 0L || logicalUpdateOrdinal < 0L) {
                throw new IllegalArgumentException("a business identity cannot name a negative lineage");
            }
        }

        /** The stable join key of this identity. */
        public String key() {
            return worldIncarnationKey + ":" + lifecycleGeneration + ":" + logicalUpdateOrdinal
                + ":" + businessKind;
        }
    }

    /** The business state of one identity; only DONE is a green completion. */
    public enum State {
        PENDING,
        READY,
        RUNNING,
        WAIT_RESULT,
        PUBLISH_PENDING,
        DONE,
        CANCELLED,
        REJECTED,
        FAULTED
    }

    /** The execution/effect ledger entry of one identity. */
    public record Effect(boolean appliedWitness, String exitWatermark) {
    }

    /** The resource ledger entry of one identity. */
    public record Resource(long ownerTokens, long arenaSlots, long queueSlots) {

        public long total() {
            return ownerTokens + arenaSlots + queueSlots;
        }
    }

    /** One reconciliation finding. */
    public record Finding(String identityKey, State state, String reason) {
    }

    /** The whole reconciliation report. */
    public record Report(long identities, long joined, long fakeGreen, List<Finding> findings) {
    }

    private final Map<String, State> business = new LinkedHashMap<>();
    private final Map<String, Boolean> input = new LinkedHashMap<>();
    private final Map<String, Effect> effects = new LinkedHashMap<>();
    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final Map<String, String> keys = new LinkedHashMap<>();

    /** Records the business ledger side of one identity. */
    public synchronized void recordBusiness(Identity identity, boolean arrived,
                                            State state) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(state, "state");
        keys.put(identity.key(), identity.key());
        business.put(identity.key(), state);
        input.put(identity.key(), arrived);
    }

    /** Records the execution/effect ledger side of one identity. */
    public synchronized void recordEffect(Identity identity, boolean applied,
                                          String exitWatermark) {
        effects.put(identity.key(), new Effect(applied,
            exitWatermark == null ? "" : exitWatermark));
    }

    /** Records the resource ledger side of one identity. */
    public synchronized void recordResource(Identity identity, Resource resource) {
        resources.put(identity.key(), Objects.requireNonNull(resource, "resource"));
    }

    /** Reconciles every recorded identity; a fake green is reported and never counted as joined. */
    public synchronized Report reconcile() {
        long joined = 0L;
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, State> entry : business.entrySet()) {
            String key = entry.getKey();
            State state = entry.getValue();
            Effect effect = effects.get(key);
            Resource resource = resources.getOrDefault(key, new Resource(0L, 0L, 0L));
            boolean green = state == State.DONE;
            if (green && (effect == null || !effect.appliedWitness()
                || effect.exitWatermark().isEmpty())) {
                findings.add(new Finding(key, state, "done-without-effect"));
            } else if ((green || state == State.CANCELLED || state == State.REJECTED)
                && resource.total() > 0L) {
                findings.add(new Finding(key, state, "terminal-holds-resource"));
            } else if (state == State.FAULTED && resource.total() == 0L) {
                findings.add(new Finding(key, state, "fault-without-obligation"));
            } else if (state == State.WAIT_RESULT && effect != null && effect.appliedWitness()) {
                findings.add(new Finding(key, state, "waiting-with-applied-effect"));
            } else {
                joined++;
            }
        }
        return new Report(business.size(), joined, findings.size(), List.copyOf(findings));
    }

    /** The number of recorded identities. */
    public synchronized int size() {
        return business.size();
    }
}
