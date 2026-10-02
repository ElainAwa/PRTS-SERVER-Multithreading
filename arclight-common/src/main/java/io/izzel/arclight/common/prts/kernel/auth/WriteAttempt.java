/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import java.util.Set;

/** One attempt to touch world state, described before it is judged. The attempt is counted the
 * moment it enters the decision point, before any judgement, so an exception behind the decision
 * point cannot erase the fact that the attempt happened. */
public record WriteAttempt(long attemptId, HolderKind holderKind, String holderSiteId, String threadRef,
                           String worldId, String declaredWorldId, WriteLevel level, String domainId,
                           WriteOp op, Set<String> declaredDomains, Set<String> observedDomains,
                           long expectedVersion, long worldEpoch, long tickIndex, long planOrder,
                           boolean siteAdmitted, boolean lifecycleChange, boolean lifecycleOwner,
                           boolean wallClockRead) {

    public WriteAttempt {
        declaredDomains = declaredDomains == null ? Set.of() : Set.copyOf(declaredDomains);
        observedDomains = observedDomains == null ? Set.of() : Set.copyOf(observedDomains);
    }

    public boolean crossWorld() {
        return !worldId.equals(declaredWorldId);
    }

    public boolean versionCarried() {
        return expectedVersion > 0L;
    }

    public static Builder builder(long attemptId, String worldId, String holderSiteId) {
        return new Builder(attemptId, worldId, holderSiteId);
    }

    public Builder toBuilder() {
        Builder builder = new Builder(attemptId, worldId, holderSiteId);
        builder.holderKind = holderKind;
        builder.threadRef = threadRef;
        builder.declaredWorldId = declaredWorldId;
        builder.level = level;
        builder.domainId = domainId;
        builder.op = op;
        builder.declaredDomains = declaredDomains;
        builder.observedDomains = observedDomains;
        builder.expectedVersion = expectedVersion;
        builder.worldEpoch = worldEpoch;
        builder.tickIndex = tickIndex;
        builder.planOrder = planOrder;
        builder.siteAdmitted = siteAdmitted;
        builder.lifecycleChange = lifecycleChange;
        builder.lifecycleOwner = lifecycleOwner;
        builder.wallClockRead = wallClockRead;
        return builder;
    }

    /** Collects the fields of an attempt so a call site reads like a statement. */
    public static final class Builder {

        private final long attemptId;
        private final String worldId;
        private final String holderSiteId;
        private HolderKind holderKind = HolderKind.UNREGISTERED;
        private String threadRef = "";
        private String declaredWorldId;
        private WriteLevel level = WriteLevel.REGION;
        private String domainId = "";
        private WriteOp op = WriteOp.WRITE;
        private Set<String> declaredDomains = Set.of();
        private Set<String> observedDomains = Set.of();
        private long expectedVersion;
        private long worldEpoch;
        private long tickIndex;
        private long planOrder;
        private boolean siteAdmitted;
        private boolean lifecycleChange;
        private boolean lifecycleOwner;
        private boolean wallClockRead;

        private Builder(long attemptId, String worldId, String holderSiteId) {
            this.attemptId = attemptId;
            this.worldId = worldId;
            this.holderSiteId = holderSiteId;
            this.declaredWorldId = worldId;
        }

        public Builder holder(HolderKind kind, String siteId, String threadRef) {
            this.holderKind = kind;
            this.threadRef = threadRef;
            return this;
        }

        public Builder target(WriteLevel level, String domainId) {
            this.level = level;
            this.domainId = domainId;
            return this;
        }

        public Builder domains(Set<String> declared, Set<String> observed) {
            this.declaredDomains = declared;
            this.observedDomains = observed;
            return this;
        }

        public Builder version(long expectedVersion, long tickIndex, long planOrder) {
            this.expectedVersion = expectedVersion;
            this.tickIndex = tickIndex;
            this.planOrder = planOrder;
            return this;
        }

        public Builder worldEpoch(long worldEpoch) {
            this.worldEpoch = worldEpoch;
            return this;
        }

        public Builder tick(long tickIndex) {
            this.tickIndex = tickIndex;
            return this;
        }

        public Builder declaredWorld(String declaredWorldId) {
            this.declaredWorldId = declaredWorldId;
            return this;
        }

        public Builder admitted(boolean siteAdmitted) {
            this.siteAdmitted = siteAdmitted;
            return this;
        }

        public Builder lifecycle(boolean change, boolean owner) {
            this.lifecycleChange = change;
            this.lifecycleOwner = owner;
            return this;
        }

        public Builder read() {
            this.op = WriteOp.READ;
            return this;
        }

        public Builder wallClockRead(boolean wallClockRead) {
            this.wallClockRead = wallClockRead;
            return this;
        }

        public WriteAttempt build() {
            return new WriteAttempt(attemptId, holderKind, holderSiteId, threadRef, worldId,
                declaredWorldId, level, domainId, op, declaredDomains, observedDomains,
                expectedVersion, worldEpoch, tickIndex, planOrder, siteAdmitted, lifecycleChange,
                lifecycleOwner, wallClockRead);
        }
    }

    /** Operation of a write attempt: a read is classified but takes no write right. */
    public enum WriteOp {

        READ,
        WRITE
    }
}
