/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compares two replayed runs under the version they declare. A shape that cannot be read is
 * refused with one of the REFUSED_ constants instead of being answered, and an empty sample is
 * refused too: two runs that wrote nothing are no evidence that they wrote the same thing. Those
 * reasons are this class's own words and not refusal codes of the write path, whose set stays
 * closed. A versioned comparison reads the logical columns of a step and never the numbers a run
 * happened to assign, and it reads no namespace column: a collision between two lifecycles is
 * decided without the thread. */
public final class ReplayComparator {

    public static final String REFUSED_SIDE_MISSING = "side-missing";
    public static final String REFUSED_UNKNOWN_SCHEMA = "unknown-schema";
    public static final String REFUSED_UNDECLARED_SCHEMA = "undeclared-schema";
    public static final String REFUSED_MIXED_PROTOCOL = "mixed-protocol";
    public static final String REFUSED_MIXED_WITHIN_SIDE = "mixed-within-side";
    public static final String REFUSED_ORPHAN_EFFECT = "orphan-effect";
    public static final String REFUSED_EFFECT_MISSING = "effect-missing";
    public static final String REFUSED_INSTANCE_COLLISION = "instance-collision";
    public static final String REFUSED_EMPTY_SAMPLE = "empty-sample";
    public static final String REFUSED_ILLEGAL_IDENTITY = "illegal-identity";

    /** The columns a versioned comparison reads, in the order it reports them. The runtime namespace
     * and the line's own report number, tick and handle are deliberately not read. */
    public static final List<String> CANONICAL_FIELDS = List.of("world", "domain", "node", "plan",
        "position", "writeSet", "rows", "epoch", "chunk", "status", "instance", "authorization",
        "effect", "operation", "outcome", "declared", "content", "contentDigest", "disposition");

    public enum Decision {
        EQUAL,
        DIFFERENT,
        REFUSED
    }

    public record Verdict(Decision decision, int differences, String reason, List<String> fields,
                          String note) {

        public Verdict {
            reason = reason == null ? "" : reason;
            fields = List.copyOf(fields == null ? List.of() : fields);
            note = note == null ? "" : note;
        }

        public boolean equal() {
            return decision == Decision.EQUAL;
        }

        public boolean refused() {
            return decision == Decision.REFUSED;
        }
    }

    private static final String LEGACY_NOTE = "declared version 0: the plan order, the position and "
        + "the write set of a plain step only; the typed columns of these records are not read";

    private static final String UNVERIFIED_NOTE = "typed columns compared without a sealed "
        + "declaration: the instance column is a first-appearance ordinal of this side and the "
        + "provenance of the records is not verified";

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    /** What one side of a strict comparison is read through: the commit label the records were taken
     * from, the integrity summary of those records, and the declarations sealed for the freeze that
     * ordered them. The closure is the receipt that ties the four together inside this library: the
     * summary has to be the one these records produce, the table has to name the plan these records
     * were ordered by, and every record has to carry the content its own digest and names say it
     * does. A closure that fails any of those is refused instead of being read as the weaker
     * historical comparison. The commit label is carried and not verified against a real artifact,
     * so what a strict reading proves is consistency among these four and not provenance: see
     * {@link #EVIDENCE_TIER}. */
    public record Evidence(String sourceCommit, String recordDigest,
                           FrozenDeclarations declarations, CommitLog.SchemaReplay replay,
                           RangeSealTable.Snapshot ranges) {

        /** The evidence of a run that sealed no range: the four historical parts and no closure. */
        public Evidence(String sourceCommit, String recordDigest, FrozenDeclarations declarations,
                        CommitLog.SchemaReplay replay) {
            this(sourceCommit, recordDigest, declarations, replay, null);
        }
    }

    /** What a strict closure proves and what it does not. The records, their summary, the sealed
     * table and the commit label are consistent with each other inside this library; the label is
     * not bound to a real artifact, so a strict EQUAL is evidence of this consistency and is not a
     * release gate. */
    public static final String EVIDENCE_TIER = "records, summary, table and label agree inside this "
        + "library; the label is not bound to a real artifact, so this is not a release gate";

    private final Map<String, Long> refusals = new LinkedHashMap<>();
    private long compared;
    private long equal;
    private long different;
    private long refused;

    /** Wraps a plain replay as a side that declares version 0, which is the only way a three-field
     * comparison is reached. A replay that is not there stays missing. */
    public CommitLog.SchemaReplay legacyDeclared(CommitLog.Replay replay) {
        if (replay == null) {
            return null;
        }
        return new CommitLog.SchemaReplay(CommitLog.SCHEMA_LEGACY, replay.steps(), List.of(), 0);
    }

    /** Compares two sides under the version they declare, through the closure each was handed in
     * with. The instance column of this reading is the canonical form of the logical key the sealed
     * declarations back, so two runs that assigned other runtime numbers still compare equal and two
     * runs whose declarations differ are never answered equal on a first-appearance ordinal. */
    public Verdict compare(Evidence left, Evidence right) {
        if (left == null || right == null || left.replay() == null || right.replay() == null) {
            return refuse(REFUSED_SIDE_MISSING, "");
        }
        String broken = closure(left);
        if (broken == null) {
            broken = closure(right);
        }
        if (broken != null) {
            return refuse(broken, "closure");
        }
        if ((left.ranges() == null) != (right.ranges() == null)) {
            // One side carries a range closure and the other reads an entry table: the two sides
            // answered two protocols, and neither is compared as the weaker one.
            return refuse(REFUSED_MIXED_PROTOCOL, "only one side carries a range closure");
        }
        return compare(left.replay(), right.replay(),
            new Strict(left.declarations(), right.declarations(), left.ranges(), right.ranges()));
    }

    /** The integrity summary of one side, computed from the records themselves. A closure that
     * carries another summary is a closure of another run. */
    public static String recordsDigest(CommitLog.SchemaReplay side) {
        if (side == null) {
            return "";
        }
        long hash = OFFSET_BASIS;
        for (CommitLog.Entry.Step step : side.plainSteps()) {
            hash = fold(hash, "p/" + step.planSequence() + "/" + step.position() + "/"
                + step.writeSetDigest());
        }
        for (CommitLog.TypedStep step : side.typedSteps()) {
            hash = fold(hash, "t/" + step.worldId() + "/" + step.domainId() + "/" + step.nodeKey()
                + "/" + step.planSequence() + "/" + step.position() + "/" + step.writeSetDigest() + "/"
                + step.rows() + "/" + step.expectedVersion() + "/" + step.disposition());
            EffectStore.Recorded effect = step.effect();
            hash = fold(hash, effect == null ? "e/-" : "e/" + effect.effectRef() + "/"
                + effect.contentDigest() + "/" + effect.operationKind() + "/" + effect.outcome() + "/"
                + String.join(",", effect.declared()) + "/" + String.join("\n", effect.content()));
            CommitLog.Identity identity = step.identity();
            hash = fold(hash, "i/" + identity.schemaVersion() + "/" + identity.instanceId() + "/"
                + identity.worldEpoch() + "/" + identity.chunkPos() + "/" + identity.targetStatus()
                + "/" + identity.authorizationGeneration() + "/" + identity.effectRef() + "/"
                + identity.producer().lifecycleKey() + "/" + identity.controller().lifecycleKey());
        }
        return Long.toHexString(hash);
    }

    private static String closure(Evidence evidence) {
        if (evidence.sourceCommit() == null || evidence.sourceCommit().isEmpty()
            || evidence.recordDigest() == null) {
            return REFUSED_ILLEGAL_IDENTITY;
        }
        if (!recordsDigest(evidence.replay()).equals(evidence.recordDigest())) {
            return REFUSED_ILLEGAL_IDENTITY;
        }
        RangeSealTable.Snapshot ranges = evidence.ranges();
        FrozenDeclarations table = evidence.declarations();
        if (ranges == null) {
            if (table == null || table.size() == 0) {
                // A table that sealed nothing is no closure: it backs no seat, so no record can be
                // read as one of its freeze however well the summary matches.
                return REFUSED_ILLEGAL_IDENTITY;
            }
            return recordsOf(evidence);
        }
        if (!ranges.wellFormed() || ranges.digest() != ranges.recompute()) {
            // The closure carries a fold its own seals do not produce: it is refused rather than
            // compared as the weaker historical side.
            return REFUSED_ILLEGAL_IDENTITY;
        }
        List<CommitLog.TypedStep> typed = evidence.replay().typedSteps();
        List<CommitLog.TypedStep> sealed = new ArrayList<>(typed.size());
        for (CommitLog.TypedStep step : typed) {
            if (ranges.has(step.planSequence(), step.position(), step.identity().chunkPos())) {
                sealed.add(step);
            }
        }
        if (table == null || table.size() == 0) {
            // A range-only closure is readable only when every typed step has a seal: a step without
            // one is a hole the closure cannot answer, and no empty table is read as a pass.
            if (ranges.empty() || sealed.size() != typed.size()) {
                return REFUSED_ILLEGAL_IDENTITY;
            }
            return null;
        }
        if (!sealed.isEmpty() && sealed.size() < typed.size()) {
            // One side cannot carry both protocols: a step read against a range and a step read from
            // an entry are two identities in one closure.
            return REFUSED_MIXED_WITHIN_SIDE;
        }
        if (sealed.size() == typed.size()) {
            if (!ranges.freeze().equals(table.freeze())
                || ranges.planSequence() != table.planSequence()) {
                return REFUSED_ILLEGAL_IDENTITY;
            }
            for (CommitLog.TypedStep step : typed) {
                if (verifyStep(step, ranges) != null) {
                    return REFUSED_ILLEGAL_IDENTITY;
                }
            }
        }
        return recordsOf(evidence);
    }

    /** The witness verdict of one typed step against the seal its seat and cell name, or the refusal
     * constant. Role, owner and stamp are not re-derived: a replay has no assignment or binding
     * input, and those were proved where the seal was built and frozen. */
    private static String verifyStep(CommitLog.TypedStep step, RangeSealTable.Snapshot ranges) {
        FrozenDeclarations.RangeSeal seal = ranges.sealAt(step.planSequence(), step.position(),
            step.identity().chunkPos());
        if (seal == null) {
            return REFUSED_ILLEGAL_IDENTITY;
        }
        CommitLog.Resolved seat = new CommitLog.Resolved(step.planSequence(), step.position(), false);
        IdentityWitness.Verdict witness = IdentityWitness.verifyRange(seal, seat, step.worldId(),
            step.domainId(), step.nodeKey(), step.identity(), null);
        return witness.accepted() ? null : REFUSED_ILLEGAL_IDENTITY;
    }

    /** Whether the records are the ones the table and the summary were taken from: every step was
     * ordered by the plan the table names, and every record carries the rows, the field names and
     * the digest its own content produces. A record whose rows were replaced while the digest that
     * came with it was kept is refused here instead of being compared as the run it claims to be. */
    private static String recordsOf(Evidence evidence) {
        FrozenDeclarations table = evidence.declarations();
        RangeSealTable.Snapshot ranges = evidence.ranges();
        for (CommitLog.TypedStep step : evidence.replay().typedSteps()) {
            EffectStore.Recorded effect = step.effect();
            if (ranges != null) {
                // A range closure is read on its own: the step has to name a seal of its own seat and
                // cell, and only a closure that also carries an entry table is judged against it.
                if (ranges.sealAt(step.planSequence(), step.position(),
                    step.identity().chunkPos()) == null
                    || (table != null && table.size() > 0
                        && step.planSequence() != table.planSequence())) {
                    return REFUSED_ILLEGAL_IDENTITY;
                }
            } else if (table == null || step.planSequence() != table.planSequence()) {
                return REFUSED_ILLEGAL_IDENTITY;
            }
            if (effect == null
                || !EffectStore.contentDigest(effect.content()).equals(effect.contentDigest())
                || !EffectStore.declaredRows(effect.content()).equals(effect.declared())) {
                return REFUSED_ILLEGAL_IDENTITY;
            }
        }
        return null;
    }

    private static long fold(long hash, String text) {
        long value = hash;
        for (int index = 0; index < text.length(); index++) {
            value ^= text.charAt(index);
            value *= PRIME;
        }
        return value;
    }

    /** Compares two sides under the version they declare. An empty sample is refused and does not
     * count as a pair the comparison passed judgement on. */
    public Verdict compare(CommitLog.SchemaReplay left, CommitLog.SchemaReplay right) {
        return compare(left, right, null);
    }

    private Verdict compare(CommitLog.SchemaReplay left, CommitLog.SchemaReplay right, Strict strict) {
        if (left == null || right == null) {
            return refuse(REFUSED_SIDE_MISSING, "");
        }
        if (left.schemaVersion() != right.schemaVersion()) {
            return refuse(REFUSED_MIXED_PROTOCOL, "left=" + left.schemaVersion()
                + " right=" + right.schemaVersion());
        }
        int version = left.schemaVersion();
        if (version == CommitLog.SCHEMA_UNDECLARED) {
            return refuse(REFUSED_UNDECLARED_SCHEMA, "the records declare no version");
        }
        if (!CommitLog.supportedSchema(version)) {
            return refuse(REFUSED_UNKNOWN_SCHEMA, "version=" + version);
        }
        if (version == CommitLog.SCHEMA_LEGACY) {
            if (!left.typedSteps().isEmpty() || !right.typedSteps().isEmpty()) {
                return refuse(REFUSED_MIXED_WITHIN_SIDE, "a version 0 side carries typed steps");
            }
            if (left.plainSteps().isEmpty() || right.plainSteps().isEmpty()) {
                return emptySample("a version 0 side carries no plain step");
            }
            return comparePlain(left.plainSteps(), right.plainSteps());
        }
        if (!left.plainSteps().isEmpty() || !right.plainSteps().isEmpty()) {
            return refuse(REFUSED_MIXED_WITHIN_SIDE, "a version 1 side carries plain steps");
        }
        String broken = integrity(left, strict == null ? null : strict.leftRanges());
        if (broken != null) {
            return refuse(broken, "left");
        }
        broken = integrity(right, strict == null ? null : strict.rightRanges());
        if (broken != null) {
            return refuse(broken, "right");
        }
        if (left.typedSteps().isEmpty() || right.typedSteps().isEmpty()) {
            return emptySample("a version 1 side carries no typed step");
        }
        return compareTyped(left.typedSteps(), right.typedSteps(), strict);
    }

    /** The two sealed declarations a strict comparison reads its instance column from; null on the
     * historical path, which has none and says so in its note. */
    private record Strict(FrozenDeclarations left, FrozenDeclarations right,
                          RangeSealTable.Snapshot leftRanges, RangeSealTable.Snapshot rightRanges) {
    }

    private Verdict comparePlain(List<CommitLog.Entry.Step> left, List<CommitLog.Entry.Step> right) {
        int differences = Math.abs(left.size() - right.size());
        int shared = Math.min(left.size(), right.size());
        List<String> fields = new ArrayList<>();
        for (int index = 0; index < shared; index++) {
            CommitLog.Entry.Step a = left.get(index);
            CommitLog.Entry.Step b = right.get(index);
            boolean stepDiffers = false;
            if (a.planSequence() != b.planSequence()) {
                stepDiffers = true;
                add(fields, "plan");
            }
            if (a.position() != b.position()) {
                stepDiffers = true;
                add(fields, "position");
            }
            if (a.writeSetDigest() != b.writeSetDigest()) {
                stepDiffers = true;
                add(fields, "writeSet");
            }
            if (stepDiffers) {
                differences++;
            }
        }
        return decide(differences, fields, LEGACY_NOTE);
    }

    private Verdict compareTyped(List<CommitLog.TypedStep> left, List<CommitLog.TypedStep> right,
                                 Strict strict) {
        Canonical leftOrdinals = new Canonical();
        Canonical rightOrdinals = new Canonical();
        List<String> leftInstances = instanceColumn(left, strict == null ? null : strict.left(),
            strict == null ? null : strict.leftRanges(), leftOrdinals);
        List<String> rightInstances = instanceColumn(right, strict == null ? null : strict.right(),
            strict == null ? null : strict.rightRanges(), rightOrdinals);
        if (leftInstances == null || rightInstances == null) {
            return refuse(REFUSED_ILLEGAL_IDENTITY, "the records of this side are not backed by the "
                + "declarations its closure carries");
        }
        int differences = Math.abs(left.size() - right.size());
        int shared = Math.min(left.size(), right.size());
        List<String> fields = new ArrayList<>();
        for (int index = 0; index < shared; index++) {
            Map<String, String> a = columns(leftOrdinals, left.get(index), leftInstances.get(index));
            Map<String, String> b = columns(rightOrdinals, right.get(index), rightInstances.get(index));
            boolean stepDiffers = false;
            for (String field : CANONICAL_FIELDS) {
                if (!Objects.equals(a.get(field), b.get(field))) {
                    stepDiffers = true;
                    add(fields, field);
                }
            }
            if (stepDiffers) {
                differences++;
            }
        }
        return decide(differences, fields, strict == null ? UNVERIFIED_NOTE : "");
    }

    /** The instance column of one side, in step order. A strict side emits the canonical form the
     * witness core answers, which is the one reconstruction point's own rendering (see
     * {@link CommitLog#logicalKeyOf(FrozenDeclarations.Entry)}); a step the verdict refuses answers
     * no column. The historical side keeps the first-appearance ordinal. */
    private static List<String> instanceColumn(List<CommitLog.TypedStep> steps,
                                               FrozenDeclarations declarations,
                                               RangeSealTable.Snapshot ranges, Canonical ordinals) {
        List<String> column = new ArrayList<>(steps.size());
        for (CommitLog.TypedStep step : steps) {
            if (ranges != null) {
                // A range row reads its column from the seal of its own seat and cell; a side with a
                // range closure never falls back to the first-appearance ordinal for that row.
                FrozenDeclarations.RangeSeal seal = ranges.sealAt(step.planSequence(), step.position(),
                    step.identity().chunkPos());
                if (seal == null) {
                    return null;
                }
                CommitLog.Resolved seat = new CommitLog.Resolved(step.planSequence(), step.position(),
                    false);
                IdentityWitness.Verdict witness = IdentityWitness.verifyRange(seal, seat,
                    step.worldId(), step.domainId(), step.nodeKey(), step.identity(), null);
                if (!witness.accepted()) {
                    return null;
                }
                column.add(witness.instance());
                continue;
            }
            if (declarations == null) {
                column.add(String.valueOf(ordinals.ordinal("instance/" + step.worldId() + "/"
                    + step.domainId() + "/" + step.identity().instanceId())));
                continue;
            }
            CommitLog.Resolved seat = new CommitLog.Resolved(step.planSequence(), step.position(),
                false);
            IdentityWitness.Verdict witness = IdentityWitness.verify(declarations, seat, step.worldId(),
                step.domainId(), step.nodeKey(), step.identity(), null);
            if (!witness.accepted()) {
                return null;
            }
            column.add(witness.instance());
        }
        return column;
    }

    private static String integrity(CommitLog.SchemaReplay side, RangeSealTable.Snapshot ranges) {
        if (side.effectMissing() > 0) {
            return REFUSED_EFFECT_MISSING;
        }
        Map<Long, String> references = new LinkedHashMap<>();
        Map<String, String> instances = new LinkedHashMap<>();
        for (CommitLog.TypedStep step : side.typedSteps()) {
            CommitLog.Identity identity = step.identity();
            EffectStore.Recorded effect = step.effect();
            long effectRef = identity.effectRef();
            if (effect == null) {
                return REFUSED_EFFECT_MISSING;
            }
            if (effectRef <= 0L || effect.effectRef() != effectRef) {
                return REFUSED_ORPHAN_EFFECT;
            }
            if (identity.schemaVersion() != CommitLog.SCHEMA_TYPED) {
                return REFUSED_MIXED_WITHIN_SIDE;
            }
            if (!identity.declaredTyped() || step.nodeKey() == null || step.nodeKey().isEmpty()
                || step.expectedVersion() != identity.authorizationGeneration()
                || step.rows() != effect.content().size()) {
                return REFUSED_ILLEGAL_IDENTITY;
            }
            String mapping = effect.contentDigest() + "/" + effect.operationKind() + "/"
                + effect.outcome();
            String carried = references.putIfAbsent(effectRef, mapping);
            if (carried != null && !carried.equals(mapping)) {
                return REFUSED_ORPHAN_EFFECT;
            }
            FrozenDeclarations.RangeSeal seal = ranges == null ? null
                : ranges.sealAt(step.planSequence(), step.position(), identity.chunkPos());
            String instance = seal == null
                ? lifecycleOf(identity) + "|" + identity.chunkPos() + "|" + identity.targetStatus()
                : lifecycleOf(identity) + "|" + seal.spaceDigest() + "|" + seal.statusMapDigest();
            String pair = step.worldId() + "/" + step.domainId() + "/" + identity.instanceId();
            String held = instances.putIfAbsent(pair, instance);
            if (held != null && !held.equals(instance)) {
                return REFUSED_INSTANCE_COLLISION;
            }
        }
        return null;
    }

    private static String lifecycleOf(CommitLog.Identity identity) {
        CommitNamespaces.Namespace producer = identity.producer();
        CommitNamespaces.Namespace controller = identity.controller();
        return producer.lifecycleKey() + "|" + controller.lifecycleKey();
    }

    private static Map<String, String> columns(Canonical ordinals, CommitLog.TypedStep step,
                                               String instance) {
        CommitLog.Identity identity = step.identity();
        EffectStore.Recorded effect = step.effect();
        String pair = step.worldId() + "/" + step.domainId();
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("world", String.valueOf(step.worldId()));
        columns.put("domain", String.valueOf(step.domainId()));
        columns.put("node", String.valueOf(step.nodeKey()));
        columns.put("plan", String.valueOf(ordinals.ordinal("plan/" + step.planSequence())));
        columns.put("position", String.valueOf(step.position()));
        columns.put("writeSet", Long.toHexString(step.writeSetDigest()));
        columns.put("rows", String.valueOf(step.rows()));
        columns.put("epoch", String.valueOf(ordinals.ordinal("epoch/" + step.worldId() + "/"
            + identity.worldEpoch())));
        columns.put("chunk", String.valueOf(identity.chunkPos()));
        columns.put("status", identity.targetStatus());
        columns.put("instance", instance);
        columns.put("authorization", String.valueOf(ordinals.ordinal("authorization/" + pair + "/"
            + identity.authorizationGeneration())));
        columns.put("effect", String.valueOf(ordinals.ordinal("effect/" + identity.effectRef())));
        columns.put("operation", effect.operationKind());
        columns.put("outcome", effect.outcome());
        columns.put("declared", String.join(",", effect.declared()));
        columns.put("content", String.join("\n", effect.content()));
        columns.put("contentDigest", effect.contentDigest());
        columns.put("disposition", String.valueOf(step.disposition()));
        return columns;
    }

    private Verdict decide(int differences, List<String> fields, String note) {
        compared++;
        if (differences == 0) {
            equal++;
            return new Verdict(Decision.EQUAL, 0, "", List.of(), note);
        }
        different++;
        return new Verdict(Decision.DIFFERENT, differences, "", fields, note);
    }

    private Verdict refuse(String reason, String note) {
        compared++;
        refused++;
        refusals.merge(reason, 1L, Long::sum);
        return new Verdict(Decision.REFUSED, 0, reason, List.of(), note);
    }

    // Refused, and not counted as a pair the comparison passed judgement on.
    private Verdict emptySample(String note) {
        refused++;
        refusals.merge(REFUSED_EMPTY_SAMPLE, 1L, Long::sum);
        return new Verdict(Decision.REFUSED, 0, REFUSED_EMPTY_SAMPLE, List.of(), note);
    }

    private static void add(List<String> fields, String field) {
        if (!fields.contains(field)) {
            fields.add(field);
        }
    }

    private static final class Canonical {

        private final Map<String, Integer> ordinals = new LinkedHashMap<>();

        int ordinal(String key) {
            Integer held = ordinals.get(key);
            if (held != null) {
                return held;
            }
            int next = ordinals.size() + 1;
            ordinals.put(key, next);
            return next;
        }
    }

    public long compared() {
        return compared;
    }

    public long equal() {
        return equal;
    }

    public long different() {
        return different;
    }

    public long refused() {
        return refused;
    }

    public long refusals(String reason) {
        return refusals.getOrDefault(reason, 0L);
    }

    public Map<String, Long> refusalReasons() {
        return new LinkedHashMap<>(refusals);
    }
}
