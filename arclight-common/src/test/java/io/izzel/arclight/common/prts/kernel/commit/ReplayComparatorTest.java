/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The versioned comparison: what it refuses instead of answering, and the logical columns it reads
 * so that two runs which assigned other runtime numbers still compare equal. */
class ReplayComparatorTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";

    private static CommitLog.OrderSource order(long plan) {
        return (worldId, domainId, nodeKey) -> {
            if (!worldId.equals(WORLD) || !domainId.equals(DOMAIN)) {
                return null;
            }
            return switch (nodeKey) {
                case "a/0" -> new CommitLog.Resolved(plan, 0, false);
                case "a/1" -> new CommitLog.Resolved(plan, 1, false);
                case "a/2" -> new CommitLog.Resolved(plan, 2, false);
                default -> null;
            };
        };
    }

    /** One report as the store would carry it: the declared columns and the rows of the effect. */
    private static CommitLog.TypedBatch report(String key, long instanceId, long epoch, long chunk,
                                               String status, long authorization, long effectRef,
                                               long lifetime, String... rows) {
        CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instanceId, epoch,
            chunk, status, authorization, effectRef,
            new CommitNamespaces.Namespace("producer", lifetime, 0L, 11L),
            new CommitNamespaces.Namespace("controller", lifetime, 0L, 11L));
        return new CommitLog.TypedBatch(new CommitLog.Batch(WORLD, DOMAIN, key, CommitLog.Batch.Kind.APPLY,
            authorization, 11L, rows.length), identity,
            new EffectStore.Effect(effectRef, "chunk-materialize", "full", List.of(rows)));
    }

    /** One step of a run that really happened with one column of the projection replaced, so a
     * counterexample can be built on the record the storage chain itself produced. */
    private static CommitLog.TypedStep replaced(CommitLog.TypedStep step, String nodeKey, int rows,
                                                 long expectedVersion, CommitLog.Identity identity,
                                                 EffectStore.Recorded effect) {
        return new CommitLog.TypedStep(step.planSequence(), step.position(), step.writeSetDigest(),
            step.worldId(), step.domainId(), nodeKey, expectedVersion, rows, identity,
            step.disposition(), effect);
    }

    /** A holder that sealed what this test declares, read off the reports the test is about to
     * make: the seats come from the order source the run was built on and the artefact from the
     * identity of the first report. */
    private static IdentityWitness.Source witnessOf(CommitLog.OrderSource source,
                                                    CommitLog.TypedBatch... reports) {
        if (reports.length == 0 || reports[0].identity() == null) {
            return IdentityWitness.NONE;
        }
        CommitLog.Identity identity = reports[0].identity();
        WitnessSource witness = new WitnessSource(source, WORLD, DOMAIN, identity.chunkPos(),
            identity.targetStatus(), identity.worldEpoch(), identity.authorizationGeneration(),
            "a/0", "a/1", "a/2");
        for (CommitLog.TypedBatch report : reports) {
            witness.declare(report.batch().nodeKey(), report.identity().chunkPos(),
                report.identity().targetStatus(), report.identity().instanceId());
        }
        return witness;
    }

    /** One run: the reports of one tick, driven through the log and read back as its versioned
     * projection. Every side of every test is built this way, on the real storage chain. */
    private static CommitLog.SchemaReplay run(CommitLog.OrderSource source,
                                              CommitLog.TypedBatch... reports) {
        CommitLog log = new CommitLog(() -> 16, source, new EffectStore(1 << 20, 64),
            witnessOf(source, reports));
        log.beginTick(1L);
        for (CommitLog.TypedBatch report : reports) {
            assertTrue(log.reachTyped(report).logged(), "the driver stores what it reports");
        }
        log.closeTick();
        assertEquals(0, log.lastTyped().effectMissing());
        return log.lastTyped();
    }

    @Test
    void theSameInputTwiceComparesEqual() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay second = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(first, second);

        assertTrue(verdict.equal());
        assertEquals(0, verdict.differences());
        assertEquals(1L, comparator.equal());
        assertEquals(0L, comparator.refused());
    }

    @Test
    void otherRuntimeNumbersAndAnotherNamespaceCompareEqual() {
        CommitLog.SchemaReplay first = run(order(4L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay second = run(order(9L), report("a/0", 7L, 21L, 64L, "full", 44L, 900L,
            5150L, "chunk=64", "state=full"));
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(first, second);

        assertTrue(verdict.equal(), "another plan number, instance number, epoch, grant and reference");
        assertEquals(0, verdict.differences());
        assertTrue(first.typedSteps().get(0).identity().producer().declared(),
            "the namespace is recorded even though it is not compared");
    }

    @Test
    void theComparedColumnsLeaveTheRuntimeNamespaceOut() {
        assertTrue(ReplayComparator.CANONICAL_FIELDS.containsAll(List.of("world", "domain", "node",
            "plan", "position", "writeSet", "rows", "epoch", "chunk", "status", "instance",
            "authorization", "effect", "operation", "outcome", "declared", "content", "contentDigest",
            "disposition")));
        assertEquals(19, ReplayComparator.CANONICAL_FIELDS.size(),
            "the compared columns are named in full and nothing else is read");
        for (String column : List.of("thread", "lifetime", "owner", "namespace", "resetEpoch",
            "expectedVersion", "sequence", "tick", "handle")) {
            assertFalse(ReplayComparator.CANONICAL_FIELDS.contains(column), column);
        }
    }

    @Test
    void aRunWithNoDeclaredVersionIsRefusedInsteadOfReadAsLegacy() {
        CommitLog log = new CommitLog(() -> 16, order(1L), new EffectStore(1 << 20, 8));
        log.beginTick(1L);
        log.closeTick();
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(log.lastTyped(), log.lastTyped());

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_UNDECLARED_SCHEMA, verdict.reason());
        assertTrue(comparator.refused() > 0L);
    }

    @Test
    void twoDeclaredLegacySidesAreComparedOnTheirThreeFieldsAndSaySo() {
        CommitLog first = plain(5L);
        CommitLog second = plain(5L);
        CommitLog changed = plain(6L);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict same = comparator.compare(comparator.legacyDeclared(first.last()),
            comparator.legacyDeclared(second.last()));
        ReplayComparator.Verdict other = comparator.compare(comparator.legacyDeclared(first.last()),
            comparator.legacyDeclared(changed.last()));

        assertTrue(same.equal());
        assertTrue(same.note().contains("version 0"), "the three-field reading says what it read");
        assertFalse(other.equal());
        assertEquals(1, other.differences());
        assertTrue(other.fields().contains("writeSet"));
    }

    private static CommitLog plain(long digest) {
        CommitLog log = new CommitLog(() -> 16, order(1L), null);
        log.beginTick(1L);
        log.reach(new CommitLog.Batch(WORLD, DOMAIN, "a/0", CommitLog.Batch.Kind.APPLY, 1L, digest, 1));
        log.closeTick();
        return log;
    }

    @Test
    void aLegacySideAgainstAVersionedOneIsRefused() {
        ReplayComparator comparator = new ReplayComparator();
        CommitLog.SchemaReplay typed = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));

        ReplayComparator.Verdict verdict = comparator.compare(comparator.legacyDeclared(plain(5L).last()),
            typed);

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_MIXED_PROTOCOL, verdict.reason());
        assertEquals(1L, comparator.refusals(ReplayComparator.REFUSED_MIXED_PROTOCOL));
    }

    @Test
    void aVersionThisBuildDoesNotKnowIsRefusedInsteadOfAnsweredEqual() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.SchemaReplay unknown = new CommitLog.SchemaReplay(7, List.of(),
            known.typedSteps(), 0);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(unknown, unknown);

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_UNKNOWN_SCHEMA, verdict.reason());
        assertEquals(1L, comparator.refusals(ReplayComparator.REFUSED_UNKNOWN_SCHEMA));
    }

    @Test
    void aLineWhoseEffectIsNotThereIsRefused() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.TypedStep step = known.typedSteps().get(0);
        CommitLog.SchemaReplay dropped = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
            List.of(replaced(step, step.nodeKey(), step.rows(), step.expectedVersion(), step.identity(),
                null)), 1);
        CommitLog.Identity orphanIdentity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L,
            "full", 3L, CommitLog.NO_EFFECT, step.identity().producer(), step.identity().controller());
        CommitLog.SchemaReplay orphan = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
            List.of(replaced(step, step.nodeKey(), step.rows(), step.expectedVersion(), orphanIdentity,
                step.effect())), 0);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict missing = comparator.compare(known, dropped);
        ReplayComparator.Verdict noReference = comparator.compare(known, orphan);

        assertTrue(missing.refused(), "a side as an import would carry it when the record did not survive");
        assertEquals(ReplayComparator.REFUSED_EFFECT_MISSING, missing.reason());
        assertTrue(noReference.refused());
        assertEquals(ReplayComparator.REFUSED_ORPHAN_EFFECT, noReference.reason());
    }

    @Test
    void twoLifecyclesWearingOneInstanceNumberAreRefused() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.TypedStep step = known.typedSteps().get(0);
        CommitLog.Identity other = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "full", 3L,
            100L, new CommitNamespaces.Namespace("producer", 999L, 1L, 12L), step.identity().controller());
        CommitLog.SchemaReplay collided = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
            List.of(step, replaced(step, step.nodeKey(), step.rows(), step.expectedVersion(), other,
                step.effect())), 0);

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(known, collided);

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_INSTANCE_COLLISION, verdict.reason());
    }

    @Test
    void oneGrantVersionOverTwoChunksIsNotOneProduct() {
        CommitLog.OrderSource source = order(1L);
        CommitLog.SchemaReplay first = run(source,
            report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L, "chunk=64", "state=full"),
            report("a/1", 2L, 5L, 65L, "full", 3L, 101L, 4242L, "chunk=65", "state=full"));
        CommitLog.SchemaReplay second = run(order(1L),
            report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L, "chunk=64", "state=full"),
            report("a/1", 2L, 5L, 64L, "full", 3L, 101L, 4242L, "chunk=64", "state=full"));
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(first, second);

        assertFalse(verdict.equal(), "sharing the authorization version does not make two chunks one");
        assertTrue(verdict.fields().contains("chunk"));
        assertEquals(1, comparator.different());
    }

    @Test
    void aRebuiltInstanceIsCaughtByTheStepsItAdded() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay rebuilt = run(order(1L),
            report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L, "chunk=64", "state=full"),
            report("a/1", 2L, 5L, 64L, "full", 3L, 101L, 4242L, "chunk=64", "state=full"));

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, rebuilt);

        assertFalse(verdict.equal(), "the rebuilt chunk is a second instance at the same place");
        assertTrue(verdict.differences() >= 1);
    }

    @Test
    void oneInstanceNameWithAnotherContentIsCaught() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay other = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=partial"));

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, other);

        assertFalse(verdict.equal(), "the name is the same and the content is not");
        assertTrue(verdict.fields().contains("content"));
        assertTrue(verdict.fields().contains("contentDigest"));
    }

    @Test
    void theDeclaredFieldSetIsComparedApartFromTheContent() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=full"));
        CommitLog.SchemaReplay other = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "phase=full"));

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, other);

        assertFalse(verdict.equal());
        assertTrue(verdict.fields().contains("declared"));
        assertTrue(verdict.fields().contains("content"));
    }

    @Test
    void anotherTargetStatusIsCaught() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.SchemaReplay other = run(order(1L), report("a/0", 1L, 5L, 64L, "empty", 3L, 100L, 4242L,
            "chunk=64"));

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, other);

        assertFalse(verdict.equal());
        assertTrue(verdict.fields().contains("status"));
    }

    @Test
    void aMissingSideIsRefusedAndTheCountersSayWhatHappened() {
        ReplayComparator comparator = new ReplayComparator();
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64"));
        CommitLog.SchemaReplay changed = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64", "state=other"));

        ReplayComparator.Verdict missing = comparator.compare(known, null);
        ReplayComparator.Verdict other = comparator.compare(known, changed);

        assertTrue(missing.refused());
        assertEquals(ReplayComparator.REFUSED_SIDE_MISSING, missing.reason());
        assertTrue(other.differences() >= 1);
        assertEquals(2L, comparator.compared());
        assertEquals(1L, comparator.refused());
        assertEquals(1L, comparator.different());
        assertEquals(1, comparator.refusalReasons().size());
        assertNotNull(comparator.refusalReasons().get(ReplayComparator.REFUSED_SIDE_MISSING));
    }

    @Test
    void aMissingReplayIsNotRebuiltAsAnEmptyLegacySample() {
        ReplayComparator comparator = new ReplayComparator();

        assertNull(comparator.legacyDeclared(null), "a replay that is not there stays missing");
        ReplayComparator.Verdict verdict = comparator.compare(comparator.legacyDeclared(plain(5L).last()),
            comparator.legacyDeclared(null));

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_SIDE_MISSING, verdict.reason());
    }

    @Test
    void twoEmptyVersionedSamplesAreRefusedInsteadOfAnsweredEqual() {
        CommitLog.SchemaReplay empty = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
            List.of(), 0);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(empty, empty);

        assertTrue(verdict.refused(), "two runs that wrote nothing are not one run");
        assertEquals(ReplayComparator.REFUSED_EMPTY_SAMPLE, verdict.reason());
        assertEquals(0L, comparator.equal());
        assertEquals(0L, comparator.compared(), "an empty sample is not a compared pair");
        assertEquals(1L, comparator.refusals(ReplayComparator.REFUSED_EMPTY_SAMPLE));
    }

    @Test
    void anIdleTickIsRefusedInBothProtocolsWithoutAddingADifference() {
        CommitLog idle = new CommitLog(() -> 4, order(1L), new EffectStore(1 << 12, 4));
        idle.beginTick(1L);
        idle.closeTick();
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict undeclared = comparator.compare(idle.lastTyped(), idle.lastTyped());
        ReplayComparator.Verdict legacy = comparator.compare(comparator.legacyDeclared(idle.last()),
            comparator.legacyDeclared(idle.last()));

        assertEquals(ReplayComparator.REFUSED_UNDECLARED_SCHEMA, undeclared.reason());
        assertEquals(ReplayComparator.REFUSED_EMPTY_SAMPLE, legacy.reason());
        assertEquals(0L, comparator.different(), "an idle tick carries no difference to report");
        assertEquals(0L, comparator.equal());
        assertEquals(1L, comparator.compared(),
            "the sample that declared a protocol and carried no step is not a compared pair");
    }

    @Test
    void aVersionedSideThatCarriesPlainStepsIsRefusedWhateverThePlainDataSays() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L,
            4242L, "chunk=64", "state=full"));
        CommitLog.SchemaReplay second = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L,
            4242L, "chunk=64", "state=full"));
        CommitLog.Entry.Step one = new CommitLog.Entry.Step(1L, 0, 11L);
        CommitLog.Entry.Step other = new CommitLog.Entry.Step(1L, 0, 99L);
        CommitLog.SchemaReplay mixed = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(one),
            first.typedSteps(), 0);
        CommitLog.SchemaReplay mixedOther = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED,
            List.of(other), second.typedSteps(), 0);

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(mixed, mixedOther);

        assertTrue(verdict.refused(), "a tick that carries two protocols is not read as one of them");
        assertEquals(ReplayComparator.REFUSED_MIXED_WITHIN_SIDE, verdict.reason());
    }

    @Test
    void aLineThatDeclaresNoUsableIdentityInsideAVersionedSideIsRefused() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L,
            4242L, "chunk=64", "state=full"));
        CommitLog.TypedStep step = known.typedSteps().get(0);
        CommitNamespaces.Namespace producer = step.identity().producer();
        CommitNamespaces.Namespace controller = step.identity().controller();
        List<CommitLog.Identity> illegal = List.of(
            new CommitLog.Identity(CommitLog.SCHEMA_UNDECLARED, 1L, 5L, 64L, "full", 3L, 100L,
                producer, controller),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, -1L, 64L, "full", 3L, 100L, producer,
                controller),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, CommitLog.NO_INSTANCE, 5L, 64L, "full", 3L,
                100L, producer, controller),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "", 3L, 100L, producer,
                controller),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "full",
                CommitLog.NO_AUTHORIZATION_GENERATION, 100L, producer, controller),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "full", 3L, 100L,
                CommitNamespaces.Namespace.NONE, CommitNamespaces.Namespace.NONE));
        ReplayComparator comparator = new ReplayComparator();

        for (CommitLog.Identity identity : illegal) {
            CommitLog.SchemaReplay side = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
                List.of(replaced(step, step.nodeKey(), step.rows(), step.expectedVersion(), identity,
                    step.effect())), 0);

            ReplayComparator.Verdict verdict = comparator.compare(known, side);

            assertTrue(verdict.refused(), "a line that declares nothing usable is not mapped");
            assertTrue(List.of(ReplayComparator.REFUSED_ILLEGAL_IDENTITY,
                ReplayComparator.REFUSED_MIXED_WITHIN_SIDE).contains(verdict.reason()), verdict.reason());
        }
        assertEquals(0L, comparator.different());
    }

    @Test
    void theBatchAndTheIdentityOfALineHaveToAgree() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L,
            4242L, "chunk=64", "state=full"));
        CommitLog.TypedStep step = known.typedSteps().get(0);
        List<CommitLog.TypedStep> inconsistent = List.of(
            replaced(step, step.nodeKey(), step.rows(), 9L, step.identity(), step.effect()),
            replaced(step, "", step.rows(), step.expectedVersion(), step.identity(), step.effect()),
            replaced(step, step.nodeKey(), step.rows() + 1, step.expectedVersion(), step.identity(),
                step.effect()));
        ReplayComparator comparator = new ReplayComparator();

        for (CommitLog.TypedStep candidate : inconsistent) {
            CommitLog.SchemaReplay side = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
                List.of(candidate), 0);

            ReplayComparator.Verdict verdict = comparator.compare(known, side);

            assertTrue(verdict.refused());
            assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, verdict.reason());
        }
    }

    @Test
    void anotherNodeOfThePlanIsNotTheSameStep() {
        CommitLog.SchemaReplay first = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L,
            4242L, "chunk=64", "state=full"));
        CommitLog.TypedStep step = first.typedSteps().get(0);
        CommitLog.SchemaReplay other = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED, List.of(),
            List.of(replaced(step, "a/1", step.rows(), step.expectedVersion(), step.identity(),
                step.effect())), 0);

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(first, other);

        assertFalse(verdict.equal(), "another node of the frozen plan is another instance");
        assertTrue(verdict.fields().contains("node"));
    }

    @Test
    void anotherThreadOfTheSameLifecycleIsNotACollision() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L,
            4242L, "chunk=64", "state=full"));
        CommitLog.TypedStep step = known.typedSteps().get(0);
        CommitLog.Identity otherThread = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L,
            "full", 3L, 100L, new CommitNamespaces.Namespace("producer", 4242L, 0L, 99L),
            new CommitNamespaces.Namespace("controller", 4242L, 0L, 99L));
        CommitLog.SchemaReplay sameLifecycle = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED,
            List.of(), List.of(replaced(step, step.nodeKey(), step.rows(), step.expectedVersion(),
                otherThread, step.effect())), 0);

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(known, sameLifecycle);

        assertTrue(verdict.equal(), "the thread column is a diagnostic and decides nothing");
    }

    @Test
    void oneReferenceThatCarriesTwoContentsIsRefused() {
        CommitLog.SchemaReplay known = run(order(1L), report("a/0", 1L, 5L, 64L, "full", 3L, 100L,
            4242L, "chunk=64", "state=full"));
        CommitLog.TypedStep step = known.typedSteps().get(0);
        EffectStore.Recorded record = step.effect();
        EffectStore.Recorded other = new EffectStore.Recorded(record.effectRef(),
            record.operationKind(), record.outcome(), List.of("chunk"), List.of("chunk=65"), "0",
            record.bytes(), record.sequence());
        CommitLog.SchemaReplay contradicting = new CommitLog.SchemaReplay(CommitLog.SCHEMA_TYPED,
            List.of(), List.of(step, replaced(step, step.nodeKey(), 1, step.expectedVersion(),
                step.identity(), other)), 0);

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(contradicting, contradicting);

        assertTrue(verdict.refused(), "one reference carries one content in one run");
        assertEquals(ReplayComparator.REFUSED_ORPHAN_EFFECT, verdict.reason());
    }

    @Test
    void aTickThatCarriesBothProtocolsIsRefusedOnTheRealChain() {
        CommitLog.TypedBatch first = report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L, "chunk=64");
        CommitLog log = new CommitLog(() -> 8, order(1L), new EffectStore(1 << 12, 8),
            witnessOf(order(1L), first));
        log.beginTick(1L);
        assertTrue(log.reachTyped(first).logged());
        assertTrue(log.reach(new CommitLog.Batch(WORLD, DOMAIN, "a/1", CommitLog.Batch.Kind.APPLY,
            3L, 12L, 1)).logged());
        log.closeTick();
        CommitLog.SchemaReplay mixed = log.lastTyped();

        assertEquals(CommitLog.SCHEMA_TYPED, mixed.schemaVersion());
        assertEquals(1, mixed.plainSteps().size());
        assertEquals(1, mixed.typedSteps().size());
        ReplayComparator.Verdict verdict = new ReplayComparator().compare(mixed, mixed);

        assertTrue(verdict.refused(), "one tick that wrote both protocols is not read as either");
        assertEquals(ReplayComparator.REFUSED_MIXED_WITHIN_SIDE, verdict.reason());
    }

    @Test
    void theEvidenceAResetKeepsIsStillComparable() {
        CommitLog first = runThenReset("chunk=64", "state=full");
        CommitLog second = runThenReset("chunk=64", "state=full");
        CommitLog changed = runThenReset("chunk=64", "state=partial");
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict same = comparator.compare(first.lastResetTyped(),
            second.lastResetTyped());
        ReplayComparator.Verdict other = comparator.compare(first.lastResetTyped(),
            changed.lastResetTyped());

        assertTrue(same.equal(), "what a reset keeps is evidence a comparison can still read");
        assertFalse(other.equal());
        assertEquals(1, other.differences());
        assertTrue(other.fields().contains("content"));
    }

    /** One run read back only through the evidence its reset kept. */
    private static CommitLog runThenReset(String... rows) {
        CommitLog.TypedBatch only = report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L, rows);
        CommitLog log = new CommitLog(() -> 4, order(1L), new EffectStore(1 << 12, 4),
            witnessOf(order(1L), only));
        log.beginTick(1L);
        assertTrue(log.reachTyped(only).logged());
        log.reset();
        return log;
    }

    // ---- range closures: the six-case matrix driven on real runs and real snapshots ----

    private static final FrozenDeclarations.FreezeKey REPLAY_FREEZE =
        new FrozenDeclarations.FreezeKey("replay/1/0", 1L);

    private static FrozenDeclarations.RangeSeal rangeSeal(long cell, String status, int role,
                                                          long instanceId, long plan,
                                                          int position) {
        RangeSealFixture fixture = new RangeSealFixture();
        fixture.world = WORLD;
        fixture.domain = DOMAIN;
        fixture.nodeKey = "a/0";
        fixture.freeze(REPLAY_FREEZE);
        fixture.cells(cell).cell(cell).status(status).role(role).instance(instanceId)
            .epoch(5L).at(plan, position);
        return fixture.build();
    }

    private static CommitLog.OrderSource rangeOrder(int position, long plan) {
        return (worldId, domainId, nodeKey) -> WORLD.equals(worldId) && DOMAIN.equals(domainId)
            && nodeKey.startsWith("a/") ? new CommitLog.Resolved(plan, position, false) : null;
    }

    private static CommitLog.TypedBatch rangeReport(FrozenDeclarations.RangeSeal seal, long effectRef) {
        CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED,
            seal.instanceId(), seal.worldEpoch(), seal.statuses().keySet().iterator().next(),
            seal.statuses().values().iterator().next().targetStatus(), 3L, effectRef,
            new CommitNamespaces.Namespace("producer", 4242L, 0L, 11L),
            new CommitNamespaces.Namespace("controller", 4242L, 0L, 11L));
        return new CommitLog.TypedBatch(new CommitLog.Batch(WORLD, DOMAIN, "a/0",
            CommitLog.Batch.Kind.APPLY, 3L, 11L, 1), identity,
            new EffectStore.Effect(effectRef, "chunk-materialize",
                identity.targetStatus(), List.of("range")));
    }

    /** One range run: every report is stored against its own seal and the snapshot of the table is
     * read before anything is released. */
    private static ReplayComparator.Evidence rangeRun(RangeSealTable table, long plan, int position,
                                                      FrozenDeclarations.RangeSeal... seals) {
        CommitLog log = new CommitLog(() -> 16, rangeOrder(position, plan),
            new EffectStore(1 << 20, 64),
            new RangeSource(table, FrozenDeclarations.none(), REPLAY_FREEZE, 5L, 3L));
        log.beginTick(1L);
        long effectRef = 100L;
        for (FrozenDeclarations.RangeSeal seal : seals) {
            assertTrue(log.reachTyped(rangeReport(seal, effectRef++)).logged(),
                "the driver stores every range report");
        }
        log.closeTick();
        CommitLog.SchemaReplay replay = log.lastTyped();
        assertEquals(0, replay.effectMissing());
        return new ReplayComparator.Evidence("commit-range", ReplayComparator.recordsDigest(replay),
            FrozenDeclarations.none(), replay, table.snapshot());
    }

    private static RangeSealTable tableOf(FrozenDeclarations.RangeSeal... seals) {
        RangeSealTable table = new RangeSealTable(16);
        for (FrozenDeclarations.RangeSeal seal : seals) {
            assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));
        }
        return table;
    }

    @Test
    void twoRangeRunsWithTheSameSealsCompareEqualOnTheirI2Instances() {
        FrozenDeclarations.RangeSeal seal = rangeSeal(64L, "full", 1, 41L, 9L, 0);
        ReplayComparator.Evidence first = rangeRun(tableOf(seal), 9L, 0, seal);
        ReplayComparator.Evidence second = rangeRun(tableOf(seal), 9L, 0, seal);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(first, second);

        assertTrue(verdict.equal());
        assertEquals(0, verdict.differences());
        assertEquals("", verdict.note(), "a strict range comparison explains no weaker reading");
        assertTrue(IdentityWitness.verifyRange(seal, new CommitLog.Resolved(9L, 0, false), WORLD,
            DOMAIN, "a/0", rangeReport(seal, 100L).identity(), null).instance().startsWith("I2"));
    }

    @Test
    void twoEntryRunsCompareEqualThroughTheirFourPartEvidence() {
        CommitLog.TypedBatch only = report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64");
        CommitLog.SchemaReplay first = run(order(1L), only);
        CommitLog.SchemaReplay second = run(order(1L), only);
        FrozenDeclarations table = declarationsOf(first);
        ReplayComparator.Evidence left = new ReplayComparator.Evidence("commit-1",
            ReplayComparator.recordsDigest(first), table, first);
        ReplayComparator.Evidence right = new ReplayComparator.Evidence("commit-2",
            ReplayComparator.recordsDigest(second), declarationsOf(second), second);
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(left, right);

        assertTrue(verdict.equal(), "the four-part evidence still reads an entry run");
        assertNull(left.ranges());
        IdentityWitness.LogicalKey key = CommitLog.logicalKeyOf(table.entries().get(0));
        assertNotNull(key);
        assertTrue(key.canonical().startsWith("I1"));
    }

    private static FrozenDeclarations declarationsOf(CommitLog.SchemaReplay replay) {
        List<FrozenDeclarations.Entry> entries = new ArrayList<>();
        int role = 0;
        for (CommitLog.TypedStep step : replay.typedSteps()) {
            entries.add(new FrozenDeclarations.Entry(new FrozenDeclarations.Seat(step.worldId(),
                step.domainId(), step.planSequence(), step.position()), step.nodeKey(), ++role,
                step.identity().chunkPos(), step.identity().targetStatus(),
                step.identity().instanceId(), step.identity().worldEpoch()));
        }
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.of(REPLAY_FREEZE,
            replay.typedSteps().get(0).planSequence(), entries);
        assertTrue(sealed.sealed());
        return sealed.declarations();
    }

    @Test
    void aRangeOnlyRunIsReadableWhenEveryStepCarriesItsSeal() {
        FrozenDeclarations.RangeSeal first = rangeSeal(64L, "full", 1, 41L, 9L, 0);
        FrozenDeclarations.RangeSeal second = rangeSeal(65L, "features", 2, 42L, 9L, 0);
        RangeSealTable table = tableOf(first, second);

        ReplayComparator.Evidence left = rangeRun(table, 9L, 0, first, second);
        ReplayComparator.Evidence right = rangeRun(tableOf(first, second), 9L, 0, first, second);

        assertTrue(left.declarations().size() == 0, "the range-only side carries no entry table");
        assertTrue(new ReplayComparator().compare(left, right).equal());
    }

    @Test
    void aRangeOnlySideWithOneStepLackingASealIsRefused() {
        FrozenDeclarations.RangeSeal first = rangeSeal(64L, "full", 1, 41L, 9L, 0);
        FrozenDeclarations.RangeSeal second = rangeSeal(65L, "features", 2, 42L, 9L, 0);
        ReplayComparator.Evidence full = rangeRun(tableOf(first, second), 9L, 0, first, second);
        ReplayComparator.Evidence broken = new ReplayComparator.Evidence(full.sourceCommit(),
            full.recordDigest(), full.declarations(), full.replay(), tableOf(first).snapshot());

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(broken, full);

        assertTrue(verdict.refused());
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, verdict.reason());
        assertFalse(verdict.equal());
    }

    @Test
    void aRangeClosureWhoseSnapshotFoldDoesNotRecomputeIsRefused() {
        FrozenDeclarations.RangeSeal seal = rangeSeal(64L, "full", 1, 41L, 9L, 0);
        RangeSealTable table = tableOf(seal);
        ReplayComparator.Evidence good = rangeRun(table, 9L, 0, seal);
        RangeSealTable.Snapshot snapshot = table.snapshot();
        RangeSealTable.Snapshot tampered = new RangeSealTable.Snapshot(snapshot.freeze(),
            snapshot.planSequence(), snapshot.seals(), snapshot.digest() + 1L);
        ReplayComparator.Evidence bad = new ReplayComparator.Evidence(good.sourceCommit(),
            good.recordDigest(), good.declarations(), good.replay(), tampered);

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(bad, good);

        assertTrue(verdict.refused(), "a digest that does not recompute is not compared");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, verdict.reason());
    }

    @Test
    void aRangeRecordWithoutItsSnapshotIsRefusedInsteadOfApproximated() {
        FrozenDeclarations.RangeSeal seal = rangeSeal(64L, "full", 1, 41L, 9L, 0);
        ReplayComparator.Evidence good = rangeRun(tableOf(seal), 9L, 0, seal);
        ReplayComparator.Evidence noClosure = new ReplayComparator.Evidence(good.sourceCommit(),
            good.recordDigest(), FrozenDeclarations.none(), good.replay());

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(noClosure, noClosure);

        assertTrue(verdict.refused(), "a range record without a range closure is not read");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, verdict.reason());
    }

    @Test
    void anEntrySideAgainstARangeSideIsRefusedAsTwoProtocols() {
        CommitLog.TypedBatch only = report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64");
        CommitLog.SchemaReplay entry = run(order(1L), only);
        ReplayComparator.Evidence entrySide = new ReplayComparator.Evidence("commit-1",
            ReplayComparator.recordsDigest(entry), declarationsOf(entry), entry);
        FrozenDeclarations.RangeSeal seal = rangeSeal(64L, "full", 1, 41L, 9L, 0);
        ReplayComparator.Evidence rangeSide = rangeRun(tableOf(seal), 9L, 0, seal);

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(entrySide, rangeSide);

        assertTrue(verdict.refused(), "one entry side and one range side are never compared");
        assertEquals(ReplayComparator.REFUSED_MIXED_PROTOCOL, verdict.reason());
    }

    @Test
    void anEntryRecordAgainstARangeClosureOfItsSeatIsRefused() {
        CommitLog.TypedBatch only = report("a/0", 1L, 5L, 64L, "full", 3L, 100L, 4242L,
            "chunk=64");
        CommitLog.SchemaReplay entry = run(order(1L), only);
        FrozenDeclarations.RangeSeal seal = rangeSeal(64L, "full", 1, 41L, 1L, 0);
        CommitLog.Resolved resolved = new CommitLog.Resolved(1L, 0, false);
        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(WORLD, DOMAIN, 1L, 0);
        assertTrue(seal.seat().equals(seat), "the range claims the entry record's own seat");
        ReplayComparator.Evidence mixed = new ReplayComparator.Evidence("commit-1",
            ReplayComparator.recordsDigest(entry), declarationsOf(entry), entry,
            tableOf(seal).snapshot());

        ReplayComparator.Verdict verdict = new ReplayComparator().compare(mixed, mixed);

        assertTrue(verdict.refused(), "an entry record is never read as a range closure");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, verdict.reason());
    }
}

