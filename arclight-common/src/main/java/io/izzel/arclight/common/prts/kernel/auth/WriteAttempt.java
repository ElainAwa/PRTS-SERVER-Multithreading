/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import java.util.Set;

/**
 * One attempt to touch world state, described before it is judged.
 *
 * <p>The attempt is counted the moment it enters the decision point, before any judgement, so an
 * exception behind the decision point cannot erase the fact that the attempt happened. Every field
 * is an input of the decision: no clock is read on the way, and the caller states whether it read
 * one itself, which is what {@code wallClockRead} reports.</p>
 *
 * @param attemptId        process-wide identifier of this attempt
 * @param holderKind       where the writing thread comes from
 * @param holderSiteId     site identity of the writer
 * @param threadRef        readable identity of the writing thread
 * @param worldId          world the access targets
 * @param declaredWorldId  world the job declared; a difference is a cross-world write
 * @param level            level of the write right domain
 * @param domainId         identity of the domain inside the level
 * @param op               read or write
 * @param declaredDomains  domain set the job declared at planning time
 * @param observedDomains  domain set the access actually touches
 * @param expectedVersion  version the writer expects in the version slot; zero means none
 * @param tickIndex        tick the attempt belongs to
 * @param planOrder        position in the order frozen at planning time
 * @param siteAdmitted     whether the site carries a complete admission record
 * @param lifecycleChange  whether the attempt changes the world set or its lifecycle
 * @param lifecycleOwner   whether the holder owns the lifecycle
 * @param wallClockRead    whether the caller read the wall clock to build this attempt
 */
public record WriteAttempt(long attemptId, HolderKind holderKind, String holderSiteId, String threadRef,
                           String worldId, String declaredWorldId, WriteLevel level, String domainId,
                           WriteOp op, Set<String> declaredDomains, Set<String> observedDomains,
                           long expectedVersion, long tickIndex, long planOrder,
                           boolean siteAdmitted, boolean lifecycleChange, boolean lifecycleOwner,
                           boolean wallClockRead) {

    public WriteAttempt {
        declaredDomains = declaredDomains == null ? Set.of() : Set.copyOf(declaredDomains);
        observedDomains = observedDomains == null ? Set.of() : Set.copyOf(observedDomains);
    }

    /** @return {@code true} when the targeted world is not the declared one */
    public boolean crossWorld() {
        return !worldId.equals(declaredWorldId);
    }

    /** @return {@code true} when the attempt carries a version to compare */
    public boolean versionCarried() {
        return expectedVersion > 0L;
    }

    /** @return a builder with the mandatory identities of an attempt already set */
    public static Builder builder(long attemptId, String worldId, String holderSiteId) {
        return new Builder(attemptId, worldId, holderSiteId);
    }

    /** @return a builder that starts from this attempt, so one field can be varied */
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
                expectedVersion, tickIndex, planOrder, siteAdmitted, lifecycleChange,
                lifecycleOwner, wallClockRead);
        }
    }
}
