/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The one eligibility predicate of the entity rows a tick offers: a closed reason set, a single
 * precedence and an immutable fact record the caller fills from the row it read. The predicate
 * touches no host object, so every census names its rows through the same vocabulary and the same
 * answer is testable from plain values. A fact a caller did not read cannot decide.
 */
package io.izzel.arclight.common.prts.support;

/**
 * The single eligibility predicate of the entity domain, and the projection of its answer onto the
 * code set each census already reports. The precedence is fixed and the first match wins: player,
 * foreign class, removed, riding or leashed, an active AI, an item or projectile callback, a class
 * without a whole-tick model, fire, fluid, neighbours, a refusing model, the class-level census
 * answer, and finally eligible.
 */
public final class EntityEligibility {

    /** Why a row is refused, or {@link #ELIGIBLE}; the declaration order is the precedence. */
    public enum Reason {
        ELIGIBLE, PLAYER, THIRD_PARTY, LIFECYCLE, RIDING, AI, CALLBACK, UNMODELED, FIRE, FLUID,
        NEIGHBOURS, MODEL, CROSS_ENTITY, UNKNOWN
    }

    /** A row fact read as true, read as false, or not read at all. */
    public enum Fact { UNREAD, NO, YES }

    /** What the model registry answered for the row, or that the caller did not ask it. */
    public enum Model { UNREAD, NONE, REPLICABLE, REFUSING }

    /** The class-level shape the cheaper census derives; a caller that did not ask passes UNREAD. */
    public enum ClassKind { UNREAD, PURE, MOB, ORB, LIVING, OTHER }

    /** The immutable facts of one row; only a fact the caller read carries information. */
    public record RowFacts(boolean player, boolean foreignClass, boolean removed, boolean riding,
                           boolean aiMob, boolean itemOrProjectile, Model model,
                           ClassKind classKind, boolean pushable, Fact fire, Fact fluid,
                           Fact neighbours) {
    }

    private EntityEligibility() {
    }

    /** The facts the class-level census reads: class shape and cheap row state, no world query. */
    public static RowFacts censusFacts(boolean player, boolean foreignClass, boolean removed,
                                       boolean riding, boolean aiMob, boolean itemOrProjectile,
                                       ClassKind classKind, boolean pushable) {
        return new RowFacts(player, foreignClass, removed, riding, aiMob, itemOrProjectile,
            Model.UNREAD, classKind, pushable, Fact.UNREAD, Fact.UNREAD, Fact.UNREAD);
    }

    /** The facts a host entry reads: the model answer and the row's interaction with the world. */
    public static RowFacts observedFacts(boolean player, boolean foreignClass, boolean removed,
                                         boolean riding, boolean aiMob, boolean itemOrProjectile,
                                         Model model, Fact fire, Fact fluid, Fact neighbours) {
        return new RowFacts(player, foreignClass, removed, riding, aiMob, itemOrProjectile, model,
            ClassKind.UNREAD, false, fire, fluid, neighbours);
    }

    /** Classifies one row: the first fact that refuses it decides, in the fixed precedence. */
    public static Reason classify(RowFacts facts) {
        if (facts.player()) {
            return Reason.PLAYER;
        }
        if (facts.foreignClass()) {
            return Reason.THIRD_PARTY;
        }
        if (facts.removed()) {
            return Reason.LIFECYCLE;
        }
        if (facts.riding()) {
            return Reason.RIDING;
        }
        if (facts.aiMob()) {
            return Reason.AI;
        }
        if (facts.itemOrProjectile()) {
            return Reason.CALLBACK;
        }
        if (facts.model() == Model.NONE) {
            return Reason.UNMODELED;
        }
        if (facts.fire() == Fact.YES) {
            return Reason.FIRE;
        }
        if (facts.fluid() == Fact.YES) {
            return Reason.FLUID;
        }
        if (facts.neighbours() == Fact.YES) {
            return Reason.NEIGHBOURS;
        }
        if (facts.model() == Model.REFUSING) {
            return Reason.MODEL;
        }
        return classReason(facts);
    }

    /** Whether the predicate admitted the row. */
    public static boolean admitted(Reason reason) {
        return reason == Reason.ELIGIBLE;
    }

    private static Reason classReason(RowFacts facts) {
        return switch (facts.classKind()) {
            case UNREAD -> Reason.ELIGIBLE;
            // A pure row holds a position and an orientation; it is in the way only while pushable.
            case PURE -> facts.pushable() ? Reason.CROSS_ENTITY : Reason.ELIGIBLE;
            // A mob row without an active AI still pushes the rows around it, as any living row.
            case MOB -> facts.pushable() ? Reason.CROSS_ENTITY : Reason.UNKNOWN;
            case ORB, LIVING -> Reason.CROSS_ENTITY;
            case OTHER -> Reason.UNKNOWN;
        };
    }

    /** The code set of the class-level census, kept identical so its evidence line keeps meaning. */
    public static final class Capability {

        public static final int ELIGIBLE = 0;
        public static final int LIFECYCLE = 1;
        public static final int AI = 2;
        public static final int RIDING = 3;
        public static final int CROSS_ENTITY = 4;
        public static final int HOST_CALLBACK = 5;
        public static final int UNKNOWN = 6;
        public static final int PLAYER = 7;
        public static final int COUNT = 8;

        private Capability() {
        }

        /** Projects one reason onto the census code set; world and model refusals count apart. */
        public static int of(Reason reason) {
            return switch (reason) {
                case ELIGIBLE -> ELIGIBLE;
                case PLAYER -> PLAYER;
                case THIRD_PARTY, CALLBACK -> HOST_CALLBACK;
                case LIFECYCLE -> LIFECYCLE;
                case RIDING -> RIDING;
                case AI -> AI;
                case FIRE, FLUID, NEIGHBOURS, MODEL, CROSS_ENTITY -> CROSS_ENTITY;
                case UNMODELED, UNKNOWN -> UNKNOWN;
            };
        }
    }

    /** The code set of the host-entry re-scope census, kept identical for its evidence line. */
    public static final class Rescope {

        public static final int WIDENED = 0;
        public static final int THIRD_PARTY = 1;
        public static final int PLAYER = 2;
        public static final int LIFECYCLE = 3;
        public static final int RIDING = 4;
        public static final int AI = 5;
        public static final int CALLBACK = 6;
        public static final int NEIGHBOURS = 7;
        public static final int FLUID = 8;
        public static final int FIRE = 9;
        public static final int UNMODELED = 10;
        public static final int COUNT = 11;

        private Rescope() {
        }

        /** Projects one reason onto the re-scope code set; a refusing model has no whole tick. */
        public static int of(Reason reason) {
            return switch (reason) {
                case ELIGIBLE -> WIDENED;
                case PLAYER -> PLAYER;
                case THIRD_PARTY -> THIRD_PARTY;
                case LIFECYCLE -> LIFECYCLE;
                case RIDING -> RIDING;
                case AI -> AI;
                case CALLBACK -> CALLBACK;
                case UNMODELED, MODEL, UNKNOWN -> UNMODELED;
                case NEIGHBOURS, CROSS_ENTITY -> NEIGHBOURS;
                case FLUID -> FLUID;
                case FIRE -> FIRE;
            };
        }
    }

    /** The code set of the takeover fixture, kept identical so its accounts keep their meaning. */
    public static final class Takeover {

        public static final int WIDENED = 0;
        public static final int THIRD_PARTY = 1;
        public static final int PLAYER = 2;
        public static final int LIFECYCLE = 3;
        public static final int RIDING = 4;
        public static final int AI = 5;
        public static final int CALLBACK = 6;
        public static final int UNMODELED = 7;
        public static final int NEIGHBOURS = 8;
        public static final int FLUID = 9;
        public static final int FIRE = 10;
        public static final int MODEL = 11;
        public static final int COUNT = 12;

        private Takeover() {
        }

        /** Projects one reason onto the takeover code set; the class-level answers stay apart. */
        public static int of(Reason reason) {
            return switch (reason) {
                case ELIGIBLE -> WIDENED;
                case PLAYER -> PLAYER;
                case THIRD_PARTY -> THIRD_PARTY;
                case LIFECYCLE -> LIFECYCLE;
                case RIDING -> RIDING;
                case AI -> AI;
                case CALLBACK -> CALLBACK;
                case UNMODELED, UNKNOWN -> UNMODELED;
                case NEIGHBOURS, CROSS_ENTITY -> NEIGHBOURS;
                case FLUID -> FLUID;
                case FIRE -> FIRE;
                case MODEL -> MODEL;
            };
        }
    }
}
