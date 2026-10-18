/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The versioned columns of a log line, the room a versioned report takes before it is stored, and
 * the state the running server is in: no effect store is bound there, so the versioned path answers
 * a refusal instead of writing a line whose effect nothing could resolve. */
class CommitLogTypedSchemaTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final long LIFETIME = 4242L;
    private static final long OTHER_LIFETIME = 5150L;

    private static CommitLog.OrderSource order(long plan) {
        return (worldId, domainId, nodeKey) -> {
            if (!worldId.equals(WORLD) || !domainId.equals(DOMAIN)) {
                return null;
            }
            return switch (nodeKey) {
                case "a/0" -> new CommitLog.Resolved(plan, 0, false);
                case "a/1" -> new CommitLog.Resolved(plan, 1, false);
                case "a/2" -> new CommitLog.Resolved(plan + 1L, 0, false);
                default -> null;
            };
        };
    }

    /** The holder stand-in of this file: the artefact every identity it builds declares. */
    private static WitnessSource declared(CommitLog.OrderSource plans) {
        return new WitnessSource(plans, WORLD, DOMAIN, 64L, "full", 5L, 3L, "a/0", "a/1", "a/2");
    }

    private static CommitLog.Batch batch(String key, long digest) {
        return new CommitLog.Batch(WORLD, DOMAIN, key, CommitLog.Batch.Kind.APPLY, 7L, digest, 3);
    }

    /** The batch of a versioned report: the authorization generation the identity carries, and the
     * row count its record carries, which is what a versioned line has to agree with itself on. */
    private static CommitLog.Batch versionedBatch(String key, long digest, int rows) {
        return new CommitLog.Batch(WORLD, DOMAIN, key, CommitLog.Batch.Kind.APPLY, 3L, digest, rows);
    }

    private static CommitNamespaces.Namespace namespace(String owner, long lifetime, long reset) {
        return new CommitNamespaces.Namespace(owner, lifetime, reset, 77L);
    }

    private static CommitLog.Identity identity(long instanceId, long chunk, String status,
                                               long authorization, long effectRef, long lifetime) {
        return new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instanceId, 5L, chunk, status,
            authorization, effectRef, namespace("producer", lifetime, 0L),
            namespace("controller", lifetime, 0L));
    }

    private static CommitLog.TypedBatch typed(String key, long effectRef, long lifetime,
                                              String... rows) {
        return new CommitLog.TypedBatch(versionedBatch(key, 11L, rows.length),
            identity(1L, 64L, "full", 3L, effectRef, lifetime),
            new EffectStore.Effect(effectRef, "chunk-materialize", "full", List.of(rows)));
    }

    private static EffectStore store() {
        return new EffectStore(4096, 16);
    }

    private static CommitLog log(EffectStore effects) {
        return new CommitLog(() -> 8, order(1L), effects, declared(order(1L)));
    }

    private static CommitLog.Entry head(CommitLog log) {
        return log.rings().get(0).peek();
    }

    @Test
    void aPlainLineKeepsItsColumnsAndDeclaresNoVersion() {
        CommitLog log = log(store());
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reach(batch("a/0", 11L));
        CommitLog.Entry entry = head(log);

        assertTrue(verdict.logged());
        assertEquals(1L, entry.sequence());
        assertEquals(10L, entry.tickIndex());
        assertEquals(1L, entry.planSequence());
        assertEquals(0, entry.position());
        assertEquals(WORLD, entry.worldId());
        assertEquals(DOMAIN, entry.domainId());
        assertEquals("a/0", entry.nodeKey());
        assertEquals(0L, entry.handle());
        assertEquals(CommitLog.Batch.Kind.APPLY, entry.kind());
        assertEquals(7L, entry.expectedVersion());
        assertEquals(11L, entry.writeSetDigest());
        assertEquals(3, entry.rows());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, entry.disposition());
        assertNull(entry.code());
        assertFalse(entry.intent());
        assertEquals(CommitLog.SCHEMA_UNDECLARED, entry.identity().schemaVersion(),
            "the plain path declares no schema and must not read as version 0");
        assertEquals(CommitLog.NO_INSTANCE, entry.identity().instanceId());
        assertEquals(CommitLog.NO_EFFECT, entry.identity().effectRef());
        assertEquals(CommitNamespaces.Namespace.NONE, entry.identity().producer());
        assertFalse(entry.identity().declaredTyped());
    }

    @Test
    void aVersionedLineCarriesTheDeclaredColumnsAndTheRecordItPointsAt() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64", "state=full"));
        CommitLog.Entry entry = head(log);

        assertTrue(verdict.logged());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, verdict.disposition());
        assertEquals(CommitLog.SCHEMA_TYPED, entry.identity().schemaVersion());
        assertEquals(1L, entry.identity().instanceId());
        assertEquals(100L, entry.identity().effectRef());
        assertEquals(5L, entry.identity().worldEpoch());
        assertEquals(64L, entry.identity().chunkPos());
        assertEquals("full", entry.identity().targetStatus());
        assertEquals(3L, entry.identity().authorizationGeneration());
        assertTrue(entry.identity().declaredTyped());
        assertEquals(1L, log.typedStored());
        assertEquals(1, effects.records());
        assertEquals(1, effects.pins(), "the line holds the record it points at");

        CommitLog.Replay plainReplay = log.closeTick();
        CommitLog.SchemaReplay schema = log.lastTyped();
        assertEquals(CommitLog.SCHEMA_TYPED, schema.schemaVersion());
        assertEquals(1, schema.typedSteps().size());
        assertEquals(1, plainReplay.steps().size(), "the same line is one step of the plain traversal");
        assertTrue(schema.plainSteps().isEmpty(), "a line that declared a version is not a plain step");
        EffectStore.Recorded record = schema.typedSteps().get(0).effect();
        assertNotNull(record);
        assertEquals(List.of("chunk=64", "state=full"), record.content());
        assertEquals(List.of("chunk", "state"), record.declared());
        assertEquals(0, effects.pins(), "the reference is released once the record was copied");
    }

    @Test
    void theVersionedPathRefusesWhileNoStoreIsBound() {
        CommitLog log = new CommitLog(() -> 8, order(1L));
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64"));

        assertFalse(verdict.logged());
        assertEquals(RejectCode.COUNTER_MISSING, verdict.code());
        assertEquals(1L, log.typedRefused());
        assertEquals(0L, log.typedStored());
        assertEquals(0L, log.sequence(), "a refused versioned report takes no report number");
        assertEquals(0, log.ringDepth());
        assertNull(log.effects());
    }

    @Test
    void aVersionedLineWithoutADeclaredSchemaOrIdentityIsRefused() {
        CommitLog log = log(store());
        log.beginTick(10L);
        CommitLog.TypedBatch good = typed("a/0", 100L, LIFETIME, "chunk=64");

        CommitLog.Verdict undeclared = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_UNDECLARED, 1L, 5L, 64L, "full", 3L, 100L,
                namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict legacy = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_LEGACY, 1L, 5L, 64L, "full", 3L, 100L,
                namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict noInstance = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, CommitLog.NO_INSTANCE, 5L, 64L, "full", 3L,
                100L, namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict noEpoch = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, CommitLog.NO_WORLD_EPOCH, 64L, "full",
                3L, 100L, namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict noStatus = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "", 3L, 100L,
                namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));
        CommitLog.Verdict noAuthorization = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "full",
                CommitLog.NO_AUTHORIZATION_GENERATION, 100L, namespace("producer", LIFETIME, 0L),
                namespace("controller", LIFETIME, 0L)), good.effect()));
        CommitLog.Verdict noNamespace = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L, "full", 3L, 100L,
                CommitNamespaces.Namespace.NONE, CommitNamespaces.Namespace.NONE), good.effect()));
        CommitLog.Verdict otherRef = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            identity(1L, 64L, "full", 3L, 999L, LIFETIME), good.effect()));

        for (CommitLog.Verdict verdict : List.of(undeclared, legacy, noInstance, noEpoch, noStatus,
            noAuthorization, noNamespace, otherRef)) {
            assertFalse(verdict.logged());
            assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        }
        assertEquals(8L, log.typedRefused());
        assertEquals(0L, log.typedStored());
        assertEquals(0, log.ringDepth());
        assertEquals(0, log.effects().records());
        assertEquals(0, log.reservedSlots(), "a refused report leaves no reservation behind");
    }

    @Test
    void aHeldRingSlotIsNotTakenByAPlainReport() {
        CommitLog log = new CommitLog(() -> 2, order(1L), store(), declared(order(1L)));
        log.beginTick(10L);
        CommitLog.RingSlot held = log.reserveSlot(WORLD, DOMAIN);

        assertNotNull(held);
        assertEquals(1, log.reservedSlots());
        assertTrue(log.reach(batch("a/0", 11L)).logged());
        CommitLog.Verdict blocked = log.reach(batch("a/1", 12L));

        assertFalse(blocked.logged(), "the plain report may not take the slot the versioned one holds");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, blocked.code());
        assertEquals(2, log.ringDepth() + log.reservedSlots());
        assertTrue(log.releaseSlot(held));
        assertTrue(log.reach(batch("a/1", 12L)).logged(),
            "once the slot is given back the plain report fits again");
        assertEquals(1L, log.reservationsTaken());
        assertEquals(1L, log.reservationsReleased());
    }

    @Test
    void aShortageOnEitherSideLeavesNeitherALineNorARecord() {
        EffectStore effects = new EffectStore(4096, 1);
        CommitLog log = new CommitLog(() -> 8, order(1L), effects, declared(order(1L))
                .declare("a/1", 65L, "full").bindNode("a/1", 2L));
        log.beginTick(10L);
        assertTrue(log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64")).logged());
        long afterFirst = log.sequence();

        CommitLog.Verdict full = log.reachTyped(new CommitLog.TypedBatch(
            versionedBatch("a/1", 12L, 1), identity(2L, 65L, "full", 3L, 101L, LIFETIME),
            new EffectStore.Effect(101L, "chunk-materialize", "full", List.of("chunk=65"))));

        assertFalse(full.logged(), "the record room is short");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, full.code());
        assertEquals(afterFirst, log.sequence(), "a refused report takes no report number");
        assertEquals(1, effects.records(), "no half record stays behind");
        assertEquals(0, effects.reservedBytes());
        assertEquals(0, log.reservedSlots());

        CommitLog tightRing = new CommitLog(() -> 1, order(1L), new EffectStore(4096, 8), declared(order(1L)));
        tightRing.beginTick(10L);
        assertTrue(tightRing.reach(batch("a/0", 11L)).logged());
        CommitLog.Verdict noRoom = tightRing.reachTyped(typed("a/1", 100L, LIFETIME, "chunk=64"));

        assertFalse(noRoom.logged(), "the ring room is short");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, noRoom.code());
        assertEquals(0, tightRing.effects().records(), "the effect is not stored for a line that did not fit");
        assertEquals(1L, tightRing.sequence(), "the refused report takes no report number");
        assertEquals(0, tightRing.reservedSlots());
    }

    @Test
    void twoLifecyclesThatCarryTheSameInstanceNumberAreRefusedInsteadOfMerged() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);

        CommitLog.Verdict first = log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64"));
        CommitLog.Verdict second = log.reachTyped(typed("a/1", 101L, OTHER_LIFETIME, "chunk=65"));

        assertTrue(first.logged());
        assertFalse(second.logged(), "the same instance number under another lifetime is another instance");
        assertEquals(RejectCode.VERSION_MISMATCH, second.code());
        assertEquals(1L, log.identityCollisions());
        assertEquals(1L, log.typedStored());
        assertEquals(1, effects.records());
        assertEquals(1, log.ringDepth());
    }

    @Test
    void aResetClearsTheReservationsAndTheVersionedCounters() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);
        log.reserveSlot(WORLD, DOMAIN);
        log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64"));
        log.reachTyped(typed("a/1", 101L, OTHER_LIFETIME, "chunk=65"));
        log.closeTick();

        log.reset();

        assertEquals(0, log.reservedSlots());
        assertEquals(0L, log.reservationsTaken());
        assertEquals(0L, log.reservationsReleased());
        assertEquals(0L, log.reservationsConsumed());
        assertEquals(0L, log.reservationRefusals());
        assertEquals(0L, log.typedStored());
        assertEquals(0L, log.typedRefused());
        assertEquals(0L, log.identityCollisions());
        assertEquals(0L, log.effectMissingSteps());
        assertNull(log.lastTyped());
        assertEquals(0L, log.sequence());
        assertEquals(1, effects.records(), "the store belongs to its own holder, not to the log reset");
    }

    @Test
    void aTickOfThePlainPathDeclaresNoVersionAndComparesAsItAlwaysDid() {
        CommitLog first = log(store());
        first.beginTick(10L);
        first.reach(batch("a/0", 11L));
        first.reach(batch("a/1", 12L));
        CommitLog.Replay firstRun = first.closeTick();
        CommitLog second = log(store());
        second.beginTick(10L);
        second.reach(batch("a/0", 11L));
        second.reach(batch("a/1", 12L));
        CommitLog.Replay secondRun = second.closeTick();

        assertEquals(0, CommitLog.compareRuns(firstRun, secondRun));
        assertEquals(CommitLog.SCHEMA_UNDECLARED, first.lastTyped().schemaVersion());
        assertTrue(first.lastTyped().typedSteps().isEmpty());
        assertEquals(2, first.lastTyped().plainSteps().size());
        assertEquals(0, first.lastTyped().effectMissing());
    }

    @Test
    void aHandleOfAnEarlierLifecycleDoesNotReleaseTheSlotOfALaterOne() {
        CommitLog log = log(store());
        log.beginTick(10L);
        CommitLog.RingSlot before = log.reserveSlot(WORLD, DOMAIN);
        assertNotNull(before);

        log.reset();
        CommitLog.RingSlot after = log.reserveSlot(WORLD, DOMAIN);

        assertNotNull(after);
        assertNotEquals(before.ticket(), after.ticket(), "a ticket is never handed out twice");
        assertTrue(before.resetEpoch() < after.resetEpoch());
        assertFalse(log.releaseSlot(before), "the handle of the ended lifecycle names no slot");
        assertEquals(1, log.reservedSlots(), "the later reservation is still held");
        assertEquals(0L, log.reservationsReleased());
        assertTrue(log.releaseSlot(after));
        assertEquals(0, log.reservedSlots());
    }

    @Test
    void aHandleOfAnotherLogOrOfAnotherPairIsNotASlotOfThisLog() {
        CommitLog first = log(store());
        CommitLog second = log(store());
        CommitLog.RingSlot held = first.reserveSlot(WORLD, DOMAIN);
        CommitLog.RingSlot other = second.reserveSlot(WORLD, DOMAIN);
        assertNotNull(held);
        assertNotNull(other);

        assertFalse(second.releaseSlot(held), "a handle of another log names no slot of this one");
        assertEquals(1, second.reservedSlots());
        CommitLog.RingSlot forged = new CommitLog.RingSlot("world-b", DOMAIN, other.ticket(),
            other.lifetime(), other.resetEpoch());
        assertFalse(second.releaseSlot(forged), "the ticket alone does not name the pair");
        assertEquals(1, second.reservedSlots());
        assertTrue(second.releaseSlot(other));
        assertEquals(1, first.reservedSlots());
    }

    @Test
    void aResetGivesBackTheReferenceOfEveryLineItEndsAndKeepsTheEvidence() {
        EffectStore effects = new EffectStore(4096, 4);
        CommitLog log = log(effects);
        log.beginTick(10L);
        assertTrue(log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64", "state=full")).logged());
        assertEquals(1, effects.pins());

        log.reset();

        assertEquals(0, effects.pins(), "the line that held the record was ended by the reset");
        assertEquals(1, effects.records(), "the record itself is not the log's to drop");
        CommitLog.SchemaReplay ended = log.lastResetTyped();
        assertNotNull(ended);
        assertEquals(CommitLog.SCHEMA_TYPED, ended.schemaVersion());
        assertEquals(1, ended.typedSteps().size());
        assertEquals(List.of("chunk=64", "state=full"), ended.typedSteps().get(0).effect().content());
        assertTrue(effects.evict(100L), "the room can be given back once the reference is gone");
        assertEquals(0, effects.bytes());
    }

    @Test
    void aResetKeepsAReferenceThatSomeoneElseHolds() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);
        assertTrue(log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64")).logged());
        assertTrue(effects.pin(100L), "a reader outside the log holds the record as well");
        assertEquals(2, effects.pins());

        log.reset();

        assertEquals(1, effects.pins(), "only the reference of the ended line is given back");
        assertFalse(effects.evict(100L), "the reference of the outside reader still holds it");
        assertTrue(effects.unpin(100L));
        assertTrue(effects.evict(100L));
    }

    @Test
    void cyclicResetsDoNotExhaustTheStore() {
        EffectStore effects = new EffectStore(4096, 2);
        CommitLog log = log(effects);

        for (long cycle = 1L; cycle <= 6L; cycle++) {
            log.beginTick(cycle);
            CommitLog.Verdict verdict = log.reachTyped(new CommitLog.TypedBatch(
                versionedBatch("a/0", 11L, 1), identity(1L, 64L, "full", 3L, 100L + cycle, LIFETIME),
                new EffectStore.Effect(100L + cycle, "chunk-materialize", "full",
                    List.of("chunk=" + cycle))));

            assertTrue(verdict.logged(), "cycle " + cycle + " still has room");
            log.reset();
            assertEquals(0, effects.pins());
            assertTrue(effects.evict(100L + cycle), "the room of cycle " + cycle + " is given back");
        }
        assertEquals(0, effects.records());
    }

    @Test
    void aVersionedReportWhoseHalvesDisagreeIsRefusedAndLeavesNothing() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);
        CommitLog.TypedBatch good = typed("a/0", 100L, LIFETIME, "chunk=64", "state=full");

        CommitLog.Verdict otherVersion = log.reachTyped(new CommitLog.TypedBatch(
            new CommitLog.Batch(WORLD, DOMAIN, "a/0", CommitLog.Batch.Kind.APPLY, 9L, 11L, 2),
            good.identity(), good.effect()));
        CommitLog.Verdict noNode = log.reachTyped(new CommitLog.TypedBatch(
            versionedBatch("", 11L, 2), good.identity(), good.effect()));
        CommitLog.Verdict otherRows = log.reachTyped(new CommitLog.TypedBatch(
            versionedBatch("a/0", 11L, 5), good.identity(), good.effect()));

        for (CommitLog.Verdict verdict : List.of(otherVersion, noNode, otherRows)) {
            assertFalse(verdict.logged());
            assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        }
        assertEquals(0L, log.sequence(), "a refused report takes no report number");
        assertEquals(0, log.ringDepth());
        assertEquals(0, log.reservedSlots());
        assertEquals(0, effects.records(), "the record of a refused line is not kept");
        assertEquals(0, effects.reservedBytes());
        assertEquals(0, effects.pins());
    }

    @Test
    void aVersionedLineWithoutALiveWorldGenerationIsRefused() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);
        CommitLog.TypedBatch good = typed("a/0", 100L, LIFETIME, "chunk=64");

        CommitLog.Verdict unlive = log.reachTyped(new CommitLog.TypedBatch(good.batch(),
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, -1L, 64L, "full", 3L, 100L,
                namespace("producer", LIFETIME, 0L), namespace("controller", LIFETIME, 0L)),
            good.effect()));

        assertFalse(unlive.logged(), "a world that is known to be absent is not a generation");
        assertEquals(RejectCode.VERSION_MISMATCH, unlive.code());
        assertFalse(CommitLog.liveWorldEpoch(-1L));
        assertFalse(CommitLog.liveWorldEpoch(CommitLog.NO_WORLD_EPOCH));
        assertTrue(CommitLog.liveWorldEpoch(1L));
        assertEquals(0, effects.records());
    }

    @Test
    void oneInstanceNumberUnderAnotherChunkOrStateIsRefused() {
        EffectStore effects = store();
        CommitLog log = new CommitLog(() -> 8, order(1L), effects,
            new WitnessSource(order(1L), WORLD, DOMAIN, 64L, "full", 5L, 3L, "a/0", "a/1")
                .declare("a/1", 65L, "full"));
        log.beginTick(10L);
        assertTrue(log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64")).logged());

        CommitLog.Verdict otherChunk = log.reachTyped(new CommitLog.TypedBatch(
            versionedBatch("a/1", 12L, 1), identity(1L, 65L, "full", 3L, 101L, LIFETIME),
            new EffectStore.Effect(101L, "chunk-materialize", "full", List.of("chunk=65"))));
        CommitLog.Verdict otherStatus = log.reachTyped(new CommitLog.TypedBatch(
            versionedBatch("a/1", 12L, 1), identity(1L, 64L, "empty", 3L, 101L, LIFETIME),
            new EffectStore.Effect(101L, "chunk-materialize", "full", List.of("chunk=65"))));

        for (CommitLog.Verdict verdict : List.of(otherChunk, otherStatus)) {
            assertFalse(verdict.logged(), "the same number names one instance, not two");
            assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        }
        assertEquals(2L, log.identityCollisions());
        assertEquals(1, effects.records());
        assertEquals(1, log.ringDepth());
    }

    @Test
    void anotherThreadIsNotAnotherLifecycleOfTheSameInstance() {
        EffectStore effects = store();
        CommitLog log = log(effects);
        log.beginTick(10L);
        CommitLog.Identity otherThread = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, 5L, 64L,
            "full", 3L, 101L, new CommitNamespaces.Namespace("producer", LIFETIME, 0L, 12L),
            new CommitNamespaces.Namespace("controller", LIFETIME, 0L, 12L));

        CommitLog.Verdict first = log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64"));
        CommitLog.Verdict second = log.reachTyped(new CommitLog.TypedBatch(
            versionedBatch("a/1", 12L, 1), otherThread,
            new EffectStore.Effect(101L, "chunk-materialize", "full", List.of("chunk=64"))));

        assertTrue(first.logged());
        assertTrue(second.logged(), "the thread a line was met on is a diagnostic, not a lifecycle");
        assertEquals(0L, log.identityCollisions());
        assertEquals(2L, log.typedStored());
    }

    @Test
    void twoVersionedReportsDoNotShareOneHeldSlot() {
        CommitLog log = new CommitLog(() -> 2, order(1L), store(), declared(order(1L))
                .declare("a/1", 65L, "full").bindNode("a/1", 2L));
        log.beginTick(10L);
        CommitLog.RingSlot held = log.reserveSlot(WORLD, DOMAIN);
        assertNotNull(held);

        assertTrue(log.reachTyped(typed("a/0", 100L, LIFETIME, "chunk=64")).logged());
        CommitLog.Verdict blocked = log.reachTyped(new CommitLog.TypedBatch(
            versionedBatch("a/1", 12L, 1), identity(2L, 65L, "full", 3L, 101L, LIFETIME),
            new EffectStore.Effect(101L, "chunk-materialize", "full", List.of("chunk=65"))));

        assertFalse(blocked.logged(), "the held slot is not room for a second versioned report");
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, blocked.code());
        assertEquals(2, log.reservedSlots() + log.ringDepth(),
            "the held slot and the stored line are still there, and the refusal added nothing");
        assertEquals(1, log.effects().records(), "the refused report stores no record");
        assertTrue(log.releaseSlot(held));
        assertTrue(log.reachTyped(new CommitLog.TypedBatch(versionedBatch("a/1", 12L, 1),
            identity(2L, 65L, "full", 3L, 101L, LIFETIME),
            new EffectStore.Effect(101L, "chunk-materialize", "full", List.of("chunk=65")))).logged());
    }

    // ---- range rows: the seat publishes a seal and the line is attributed against it ----

    private static final String RANGE_WORLD = RangeSealFixture.WORLD;
    private static final String RANGE_DOMAIN = RangeSealFixture.DOMAIN;
    private static final String RANGE_NODE = "chunk-pipeline/" + RANGE_WORLD + "/"
        + RangeSealFixture.MAILBOX + "#" + RangeSealFixture.FREEZE_ORDINAL;
    private static final long RANGE_LIFETIME = 4242L;
    private static final long RANGE_AUTHORIZATION = 3L;
    private static final FrozenDeclarations.FreezeKey RANGE_FREEZE =
        new FrozenDeclarations.FreezeKey(RangeSealFixture.LIFECYCLE, 1L);

    private static CommitLog.OrderSource rangeOrder(int position) {
        return (worldId, domainId, nodeKey) -> RANGE_WORLD.equals(worldId)
            && RANGE_DOMAIN.equals(domainId) && RANGE_NODE.equals(nodeKey)
            ? new CommitLog.Resolved(RangeSealFixture.PLAN, position, false) : null;
    }

    private static CommitLog.Identity rangeIdentity(FrozenDeclarations.RangeSeal seal, long cell,
                                                    String status, long instance, long effectRef) {
        return new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, seal.worldEpoch(), cell,
            status, RANGE_AUTHORIZATION, effectRef,
            new CommitNamespaces.Namespace("producer", RANGE_LIFETIME, 0L, CommitNamespaces.NO_THREAD),
            new CommitNamespaces.Namespace("controller", RANGE_LIFETIME, 0L,
                CommitNamespaces.NO_THREAD));
    }

    private static CommitLog.TypedBatch rangeTyped(FrozenDeclarations.RangeSeal seal, long cell,
                                                   String status, long instance, long effectRef) {
        return rangeTypedAt(seal, RANGE_NODE, cell, status, instance, effectRef);
    }

    private static CommitLog.TypedBatch rangeTypedAt(FrozenDeclarations.RangeSeal seal, String node,
                                                     long cell, String status, long instance,
                                                     long effectRef) {
        return new CommitLog.TypedBatch(new CommitLog.Batch(RANGE_WORLD, RANGE_DOMAIN, node,
            CommitLog.Batch.Kind.APPLY, RANGE_AUTHORIZATION, 11L, 1), rangeIdentity(seal, cell,
            status, instance, effectRef), new EffectStore.Effect(effectRef, "chunk-materialize", status,
            List.of("chunk=" + cell)));
    }

    private static CommitLog rangeLog(RangeSealTable table, int position,
                                      FrozenDeclarations declarations) {
        return new CommitLog(() -> 8, rangeOrder(position), new EffectStore(4096, 16),
            new RangeSource(table, declarations, RANGE_FREEZE, RangeSealFixture.EPOCH,
                RANGE_AUTHORIZATION));
    }

    @Test
    void aRangeLineIsStoredUnderItsI2InstanceAndHoldsTheSealObligation() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        RangeSealTable table = new RangeSealTable(8);
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));
        CommitLog log = rangeLog(table, seal.seat().position(), FrozenDeclarations.none());
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(rangeTyped(seal, RangeSealFixture.CELL,
            RangeSealFixture.STATUS, seal.instanceId(), 100L));

        assertTrue(verdict.logged());
        assertEquals(1L, log.accepted());
        assertEquals(1L, log.typedStored());
        assertEquals(0L, log.typedRefused());
        assertEquals(1, log.unreadObligations());
        assertEquals(1L, log.sequence());
        assertEquals(1, table.pinnedCount(), "the stored line holds the range seal");
        IdentityWitness.Verdict witness = IdentityWitness.verifyRange(seal,
            new CommitLog.Resolved(seal.planSequence(), seal.seat().position(), false), RANGE_WORLD,
            RANGE_DOMAIN, RANGE_NODE, rangeIdentity(seal, RangeSealFixture.CELL,
                RangeSealFixture.STATUS, seal.instanceId(), 100L), null);
        assertTrue(witness.accepted());
        assertTrue(witness.instance().startsWith("I2"));
        assertEquals(CommitLog.logicalKeyOf(seal).canonical(), witness.instance());
        assertTrue(IdentityWitness.parse(witness.instance()).ranged());
    }

    @Test
    void aLineThatWearsAnotherInstanceNumberThanItsSealsIsRefused() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        RangeSealTable table = new RangeSealTable(8);
        table.publish(seal);
        CommitLog log = rangeLog(table, seal.seat().position(), FrozenDeclarations.none());
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(rangeTyped(seal, RangeSealFixture.CELL,
            RangeSealFixture.STATUS, seal.instanceId() + 1L, 100L));

        assertFalse(verdict.logged());
        assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        assertEquals(0L, log.accepted());
        assertEquals(1L, log.typedRefused());
        assertEquals(0, log.unreadObligations());
        assertEquals(0, log.ringDepth());
        assertEquals(0, log.effects().records());
    }

    @Test
    void aSeatThatPublishedARangeRefusesAnEntryLineInsteadOfReadingTheOlderTable() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        RangeSealTable table = new RangeSealTable(8);
        table.publish(seal);
        FrozenDeclarations.Entry entry = new FrozenDeclarations.Entry(seal.seat(), seal.nodeKey(), 1,
            RangeSealFixture.CELL2, "full", 444L, seal.worldEpoch());
        FrozenDeclarations.Sealed old = FrozenDeclarations.of(seal.freeze(), seal.planSequence(),
            List.of(entry));
        assertTrue(old.sealed());
        CommitLog log = rangeLog(table, seal.seat().position(), old.declarations());
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(rangeTyped(seal, RangeSealFixture.CELL2, "full",
            444L, 100L));

        assertFalse(verdict.logged());
        assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        assertEquals(0L, log.accepted());
        assertEquals(1L, log.typedRefused());
        assertEquals(0, log.unreadObligations());
        assertEquals(0, log.ringDepth());
        assertEquals(1, table.size(), "the published seal is left where it was");
    }

    @Test
    void aTamperedRangeFoldIsRefusedAndTakesNoSlotOrEffect() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture()
            .spaceDigest("00000000deadbeef").build();
        RangeSealTable table = new RangeSealTable(8);
        table.publish(seal);
        CommitLog log = rangeLog(table, seal.seat().position(), FrozenDeclarations.none());
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(rangeTyped(seal, RangeSealFixture.CELL,
            RangeSealFixture.STATUS, seal.instanceId(), 100L));

        assertFalse(verdict.logged());
        assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        assertEquals(0, log.ringDepth());
        assertEquals(0, log.reservedSlots());
        assertEquals(0, log.effects().records());
        assertEquals(0L, log.typedStored());
        assertEquals(1L, log.typedRefused());
    }

    @Test
    void aRangeSeatWhoseLineCellHasNoSealIsRefusedWithoutTakingASlot() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        RangeSealTable table = new RangeSealTable(8);
        table.publish(seal);
        CommitLog log = rangeLog(table, seal.seat().position(), FrozenDeclarations.none());
        log.beginTick(10L);

        CommitLog.Verdict verdict = log.reachTyped(rangeTyped(seal, RangeSealFixture.CELL2,
            RangeSealFixture.STATUS, seal.instanceId(), 100L));

        assertFalse(verdict.logged());
        assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        assertEquals(1L, log.typedRefused());
        assertEquals(0L, log.accepted());
        assertEquals(0, log.reservedSlots());
        assertEquals(0, log.ringDepth());
    }

    @Test
    void oneInstanceNumberUnderTwoRangesOfOneCellIsRefusedInsteadOfMerged() {
        // Two ranges cover the same cell and prove the same status there; only their folds differ.
        // An attribution that read the chunk and the status would merge the two instances.
        RangeSealFixture firstFixture = new RangeSealFixture();
        firstFixture.nodeKey = "a/0";
        firstFixture.cells(RangeSealFixture.CELL, RangeSealFixture.CELL2)
            .cell(RangeSealFixture.CELL).status(RangeSealFixture.STATUS)
            .instance(RangeSealFixture.INSTANCE).at(RangeSealFixture.PLAN, 0);
        RangeSealFixture secondFixture = new RangeSealFixture();
        secondFixture.nodeKey = "a/1";
        secondFixture.cells(RangeSealFixture.CELL, RangeSealFixture.CELL2 + 2L)
            .cell(RangeSealFixture.CELL).status(RangeSealFixture.STATUS)
            .instance(RangeSealFixture.INSTANCE).at(RangeSealFixture.PLAN, 1);
        FrozenDeclarations.RangeSeal first = firstFixture.build();
        FrozenDeclarations.RangeSeal second = secondFixture.build();
        RangeSealTable table = new RangeSealTable(8);
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(first));
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(second));
        CommitLog.OrderSource plans = (worldId, domainId, nodeKey) -> RANGE_WORLD.equals(worldId)
            && RANGE_DOMAIN.equals(domainId)
            ? switch (nodeKey) {
                case "a/0" -> new CommitLog.Resolved(RangeSealFixture.PLAN, 0, false);
                case "a/1" -> new CommitLog.Resolved(RangeSealFixture.PLAN, 1, false);
                default -> null;
            } : null;
        CommitLog log = new CommitLog(() -> 8, plans, new EffectStore(4096, 16),
            new RangeSource(table, FrozenDeclarations.none(), RANGE_FREEZE, RangeSealFixture.EPOCH,
                RANGE_AUTHORIZATION));
        log.beginTick(10L);
        assertEquals(RangeSealFixture.CELL, first.statuses().keySet().iterator().next());
        assertTrue(log.reachTyped(rangeTypedAt(first, "a/0", RangeSealFixture.CELL,
            RangeSealFixture.STATUS, first.instanceId(), 100L)).logged());

        CommitLog.Verdict verdict = log.reachTyped(rangeTypedAt(second, "a/1", RangeSealFixture.CELL,
            RangeSealFixture.STATUS, first.instanceId(), 101L));

        assertFalse(verdict.logged(), "the same number under two ranges is two instances");
        assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        assertEquals(1L, log.identityCollisions());
        assertEquals(1L, log.typedStored());
        assertEquals(1, log.effects().records(), "the refused line stores no second record");
        assertEquals(1, log.ringDepth());
    }

    @Test
    void anEntryLineKeepsItsGoldenI1CanonicalAndItsInstance() {
        CommitLog log = log(store());
        log.beginTick(10L);
        CommitLog.TypedBatch line = typed("a/0", 100L, LIFETIME, "chunk=64", "state=full");

        CommitLog.Verdict verdict = log.reachTyped(line);

        assertTrue(verdict.logged());
        assertEquals(1L, log.typedStored());
        assertEquals(0, log.unreadObligations(),
            "this stand-in keeps no declaration history to owe");
        String golden = "I17:world-a6:entity3:a/0#1;#64;4:full";
        IdentityWitness.LogicalKey parsed = IdentityWitness.parse(golden);
        assertNotNull(parsed);
        assertFalse(parsed.ranged());
        assertEquals(golden, parsed.canonical());
        WitnessSource source = declared(order(1L));
        IdentityWitness.Verdict witness = IdentityWitness.verify(source.declarations(WORLD, DOMAIN,
            1L), new CommitLog.Resolved(1L, 0, false), WORLD, DOMAIN, "a/0", line.identity(), null);
        assertTrue(witness.accepted());
        assertEquals(golden, witness.instance());
        assertTrue(witness.instance().startsWith("I1"));
        assertFalse(IdentityWitness.parse(witness.instance()).ranged(),
            "an entry line is never read as a range line");
    }
}
