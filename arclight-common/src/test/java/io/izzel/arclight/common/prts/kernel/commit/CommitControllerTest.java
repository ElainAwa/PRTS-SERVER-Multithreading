/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one control path of a controlled write, exercised on a synthetic publish action: what a
 * reservation takes before the side effect, what each terminal does with it, where a reset may
 * happen, and what a refusal leaves behind. No host supplier and no world take part. */
class CommitControllerTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 9L;
    private static final long OTHER_LIFETIME = 5150L;

    /** A plan that can be rebuilt under a line that already took its place. */
    private static final class Plans implements CommitLog.OrderSource {

        long plan = 7L;
        int position = 0;
        int resolveCalls;

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            resolveCalls++;
            if (!WORLD.equals(worldId) || !DOMAIN.equals(domainId) || nodeKey == null) {
                return null;
            }
            return switch (nodeKey) {
                case "a/0" -> new CommitLog.Resolved(plan, position, false);
                case "a/1" -> new CommitLog.Resolved(plan, position + 1, false);
                case "a/2" -> new CommitLog.Resolved(plan + 1L, 0, false);
                default -> null;
            };
        }
    }

    private static final class Fixture {

        final Plans plans = new Plans();
        final EffectStore store;
        final CommitLog log;
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final WitnessSource witness;
        final CommitReservation reservations;

        Fixture(int ringCap, int storeBytes, int storeRecords) {
            store = new EffectStore(storeBytes, storeRecords);
            witness = new WitnessSource(plans, WORLD, DOMAIN, 3L, "published", 5L,
                () -> plans.plan, NODE, "a/1", "a/2");
            log = new CommitLog(() -> ringCap, plans, store, witness);
            reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
        }

        /** One line this fixture's handle declares and reports: the declaration names the instance
         * it mints for that node before the line is judged, which is what the handle of a domain
         * does. The cases where a line wears a mapping its declaration does not carry are the
         * negative cases of the witness suite and not this fixture. */
        CommitTicket.ReserveRequest request(String node, long plan, int position, int rows,
                                            long effectRef, long instance) {
            witness.bindNode(node, instance);
            CommitNamespaces.Namespace producer = new CommitNamespaces.Namespace("producer",
                OTHER_LIFETIME, 0L, 77L);
            CommitNamespaces.Namespace controller = new CommitNamespaces.Namespace("owner-a",
                reservations.controller().lifetime(), epochs.current(), 91L);
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, 5L,
                3L, "published", plan, effectRef, producer, controller);
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, node, CommitLog.Batch.Kind.APPLY,
                plan, position, plan, 4242L, rows, identity, controller,
                effect(effectRef, "publish", new String[] {"a=1", "b=2"}, rows),
                effect(effectRef, "publish", new String[] {"p=1", "p=2"}, rows),
                effect(effectRef, "publish", new String[] {"e=1", "e=2"}, rows));
        }

        CommitTicket.ReserveRequest request(int rows) {
            return request(NODE, plans.plan, plans.position, rows, EFFECT, 11L);
        }

        /** The same line with the world generation and the controller namespace the case names, so
         * the named refusals of the reservation are driven without a second fixture. The artefact,
         * the plan identity and the effect stay exactly what the other requests carry. */
        CommitTicket.ReserveRequest requestWith(String node, long worldEpoch,
                                                CommitNamespaces.Namespace controller) {
            witness.bindNode(node, 11L);
            CommitNamespaces.Namespace producer = new CommitNamespaces.Namespace("producer",
                OTHER_LIFETIME, 0L, 77L);
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 11L,
                worldEpoch, 3L, "published", plans.plan, EFFECT, producer, controller);
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, node, CommitLog.Batch.Kind.APPLY,
                plans.plan, plans.position, plans.plan, 4242L, 2, identity, controller,
                effect(EFFECT, "publish", new String[] {"a=1", "b=2"}, 2),
                effect(EFFECT, "publish", new String[] {"p=1", "p=2"}, 2),
                effect(EFFECT, "publish", new String[] {"e=1", "e=2"}, 2));
        }
    }

    private static EffectStore.Effect effect(long ref, String operation, String[] rows, int count) {
        List<String> content = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            content.add(index < rows.length ? rows[index] : "extra=" + index);
        }
        return new EffectStore.Effect(ref, operation, "declared", content);
    }

    private static CommitTicket.Outcome outcome(CommitTicket.Terminal terminal, int units) {
        return new CommitTicket.Outcome(terminal, "publish", List.of("a=1", "b=2"), units, "");
    }

    private static CommitLog.Batch batch(String node) {
        return new CommitLog.Batch(WORLD, DOMAIN, node, CommitLog.Batch.Kind.APPLY, 7L, 4242L, 2);
    }

    @Test
    void aResetInsideThePublishActionIsRefusedAndTheControllerEndsNoLifecycle() {
        Fixture f = new Fixture(2, 4096, 4);
        long epoch = f.epochs.current();
        CommitController[] holder = new CommitController[1];
        AtomicReference<String> during = new AtomicReference<>();
        holder[0] = new CommitController(f.reservations, ticket -> {
            CommitController.ResetDecision decision = holder[0].enterReset();
            during.set(decision.reason() + "/" + (f.epochs.current() - epoch) + "/"
                + f.reservations.outstanding() + "/" + f.reservations.publishing() + "/"
                + f.log.reservedSlots() + "/" + f.store.capacitySlots());
            return outcome(CommitTicket.Terminal.PUBLISHED, 3);
        });
        CommitController.SubmitResult result = holder[0].submit(f.request(2));

        assertEquals("reentrant-publish/0/1/1/1/1", during.get(),
            "mid-publish nothing moved: not the epoch, not the ticket, not one reservation");
        assertTrue(result.admitted());
        assertEquals(CommitTicket.Terminal.PUBLISHED, result.terminal());
        assertEquals(0L, f.epochs.current() - epoch,
            "the refused request moves no generation, here or later on its own");
        assertEquals(0L, holder[0].resets());
        assertEquals(1L, holder[0].deferredResets(), "the request is counted where it was refused");
        assertEquals(1L, holder[0].resetReentrantRefusals());
        assertEquals(0, f.reservations.outstanding());
        assertEquals(0, f.log.reservedSlots());
        assertEquals(0, f.store.capacitySlots());
        assertEquals(1L, f.log.sequence(), "the line the action wrote is still the line of this log");
        assertNull(f.log.lastResetTyped(),
            "and the log carries no terminal projection at all: the controller ended no lifecycle");

        CommitController.ResetDecision granted = holder[0].enterReset();
        assertTrue(granted.granted(), "the safe point after the action is where the request lands");
        assertEquals(1L, granted.epoch(), "the generation moves there, and once");
        assertEquals(1L, f.log.sequence(),
            "the log of the chain still carries its line: ending a lifecycle is not the controller's");

        CommitReservation.FinalizeResult late = f.reservations.finalize(result.ticket(),
            outcome(CommitTicket.Terminal.PUBLISHED, 3));
        assertFalse(late.finalized(), "a terminal is never rewritten");
        assertEquals("unknown-ticket", late.diagnostic());
        assertEquals(1L, f.reservations.publishedTotal());
    }

    @Test
    void aTicketOfAnEarlierGenerationIsRefusedInsteadOfQuietlyDropped() {
        Fixture f = new Fixture(2, 4096, 4);
        CommitTicket ticket = f.reservations.reserve(f.request(2)).ticket();
        f.epochs.advance();

        CommitReservation.FinalizeResult result = f.reservations.finalize(ticket,
            outcome(CommitTicket.Terminal.PUBLISHED, 1));
        assertFalse(result.finalized());
        assertEquals("stale-ticket", result.diagnostic());
        assertEquals(1L, f.reservations.staleRefusals());
        assertEquals(1, f.reservations.outstanding(), "the refused terminal left the ticket where it was");
    }

    @Test
    void onlyASuccessfulPublishCountsAsPublished() {
        Fixture success = new Fixture(2, 4096, 4);
        CommitController published = new CommitController(success.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 3));
        CommitController.SubmitResult first = published.submit(success.request(2));
        assertEquals(1, first.successBatch());
        assertEquals(3L, first.unitCount());
        assertEquals(1L, success.reservations.publishedTotal());
        assertEquals(1L, published.acceptedPublished());
        assertEquals(3L, published.publishedUnits());
        assertEquals(0L, success.reservations.partialTotal());

        Fixture partial = new Fixture(2, 4096, 4);
        CommitController half = new CommitController(partial.reservations,
            ticket -> outcome(CommitTicket.Terminal.PARTIAL, 1));
        CommitController.SubmitResult second = half.submit(partial.request(2));
        assertEquals(0, second.successBatch(), "a partial publish is not a successful batch");
        assertEquals(0L, partial.reservations.publishedTotal());
        assertEquals(1L, partial.reservations.partialTotal());
        assertEquals(1, partial.log.ringDepth(), "a partial publish still writes its terminal line");

        Fixture failed = new Fixture(2, 4096, 4);
        CommitController broken = new CommitController(failed.reservations,
            ticket -> outcome(CommitTicket.Terminal.FAILED, 0));
        CommitController.SubmitResult third = broken.submit(failed.request(2));
        assertEquals(0, third.successBatch());
        assertEquals(1L, failed.reservations.failedTotal());
        assertEquals(0L, failed.reservations.publishedTotal());

        Fixture thrown = new Fixture(2, 4096, 4);
        CommitController throwing = new CommitController(thrown.reservations, ticket -> {
            throw new IllegalStateException("the publish action failed");
        });
        CommitController.SubmitResult fourth = throwing.submit(thrown.request(2));
        // An action that threw may already have written what it was doing, and nothing here can tell
        // whether it did: the terminal is the partial one, because a failure would claim it did not.
        assertEquals(CommitTicket.Terminal.PARTIAL, fourth.terminal());
        assertEquals(1L, throwing.publishFailures());
        assertEquals(1L, throwing.supplierCalls());
        assertEquals(0L, thrown.reservations.publishedTotal());
        assertEquals(1L, thrown.reservations.partialTotal());
        assertEquals(0L, thrown.reservations.failedTotal());
        assertEquals("publisher-threw", thrown.reservations.terminalLedger().get(0).note());
    }

    @Test
    void finalizeAsksNoPlanAndTakesNoCapacity() {
        Fixture f = new Fixture(4, 4096, 4);
        AtomicReference<String> during = new AtomicReference<>();
        CommitController controller = new CommitController(f.reservations, ticket -> {
            during.set(f.plans.resolveCalls + "/" + f.log.reservationsTaken() + "/" + f.store.tickets());
            return outcome(CommitTicket.Terminal.PUBLISHED, 2);
        });
        CommitController.SubmitResult result = controller.submit(f.request(2));

        assertEquals(during.get(), f.plans.resolveCalls + "/" + f.log.reservationsTaken() + "/"
            + f.store.tickets(), "finalize resolved nothing and reserved nothing a second time");
        assertEquals(1L, f.log.reservationsTaken());
        assertEquals(1L, f.log.reservationsConsumed());
        assertEquals(1L, f.store.capacityFinalized());
        assertTrue(result.admitted());
    }

    @Test
    void aRefusedRequestTakesNothingAndCallsNoPublishAction() {
        Fixture f = new Fixture(2, 4096, 2);
        CommitController controller = new CommitController(f.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 1));
        assertTrue(controller.submit(f.request("a/0", 7L, 0, 2, EFFECT, 11L)).admitted());

        int slots = f.log.reservedSlots();
        int capacitySlots = f.store.capacitySlots();
        int reservedBytes = f.store.reservedBytes();
        int capacityBytes = f.store.capacityReservedBytes();
        int capacityRecords = f.store.capacityReservedRecords();
        long sequence = f.log.sequence();
        CommitController.SubmitResult refused = controller.submit(f.request("a/1", 7L, 1, 2, 12L, 12L));

        assertFalse(refused.admitted());
        assertEquals("effect-room", refused.diagnostic(), "the room of the record is what ran out");
        assertEquals(1L, controller.supplierCalls(), "only the accepted write reached the action");
        assertEquals(slots, f.log.reservedSlots());
        assertEquals(capacitySlots, f.store.capacitySlots());
        assertEquals(reservedBytes, f.store.reservedBytes());
        assertEquals(capacityBytes, f.store.capacityReservedBytes());
        assertEquals(capacityRecords, f.store.capacityReservedRecords());
        assertEquals(sequence, f.log.sequence(), "a refused reservation takes no report number");
        assertEquals(1, f.store.records(), "the refused reservation left no record behind");

        CommitController.SubmitResult unplanned = controller.submit(f.request("a/2", 7L, 0, 2, 13L, 13L));
        assertFalse(unplanned.admitted());
        assertEquals("plan-ref-mismatch", unplanned.diagnostic());
        assertEquals(1L, controller.supplierCalls());
        assertEquals(sequence, f.log.sequence());
    }

    @Test
    void theRoomOfTheRingAndThePlaceOfThePlanAreTwoColumns() {
        Fixture f = new Fixture(2, 4096, 4);
        CommitController controller = new CommitController(f.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 1));
        CommitTicket.ReserveRequest ahead = f.request("a/1", 7L, 1, 2, EFFECT, 11L);
        assertTrue(f.reservations.reserve(ahead).admitted(), "one of the two slots is held");

        f.log.beginTick(1);
        CommitLog.Verdict before = f.log.reach(batch("a/0"));
        assertTrue(before.logged(), "the place before the reservation is still free to the plain path");
        assertEquals(1, f.log.ringDepth(), "the remaining slot was used, not the reserved one");
        assertEquals(0, f.log.ringCapacity() - f.log.ringDepth() - f.log.reservedSlots(),
            "both slots are accounted for: one holds a line, one holds a reservation");
        assertEquals(0L, f.log.refusedFull());

        long sequence = f.log.sequence();
        CommitLog.Verdict duplicate = f.log.reach(batch("a/1"));
        assertFalse(duplicate.logged());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, duplicate.code(),
            "a duplicated place is an order refusal, not a full ring");
        assertEquals(1L, f.log.hybridOrderRefusals());
        assertEquals(1, f.log.ringDepth(), "the refused line took no slot");
        assertEquals(sequence + 1L, f.log.sequence(), "a plain refusal still takes its number");

        f.plans.position = 2;
        assertNull(f.reservations.reserve(f.request("a/0", 7L, 2, 2, EFFECT, 11L)).ticket(),
            "both slots are taken: the ring has no room left");
        assertEquals("ring-room", f.reservations.reserve(f.request("a/0", 7L, 2, 2, EFFECT, 11L))
            .diagnostic());

        Fixture full = new Fixture(1, 4096, 4);
        assertNotNull(full.reservations.reserve(full.request("a/1", 7L, 1, 2, EFFECT, 11L)).ticket());
        full.log.beginTick(1);
        CommitLog.Verdict crowded = full.log.reach(batch("a/0"));
        assertFalse(crowded.logged());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, crowded.code(),
            "a ring with no room is a capacity refusal, and says so with its own code");
    }

    @Test
    void aCancelledReservationGivesItsPlaceBackToThePlainPath() {
        Fixture f = new Fixture(2, 4096, 4);
        CommitTicket ticket = f.reservations.reserve(f.request("a/1", 7L, 1, 2, EFFECT, 11L)).ticket();
        f.log.beginTick(1);
        assertFalse(f.log.reach(batch("a/1")).logged(), "the place is held while the ticket lives");

        long sequence = f.log.sequence();
        assertTrue(f.reservations.release(ticket, CommitTicket.ReleaseReason.CANCELLED).released());
        assertEquals(0, f.log.heldPlans());
        assertEquals(sequence, f.log.sequence(), "a give-back writes no line and takes no number");
        assertTrue(f.log.reach(batch("a/1")).logged(), "the place is free again");
        assertEquals(1L, f.reservations.releasedCount());
        assertEquals("RELEASED", f.reservations.terminalLedger().get(0).state());
    }

    @Test
    void aRebuiltPlanGivesAnUnstartedTicketBackAndLeavesAPublishingOneAlone() {
        Fixture f = new Fixture(2, 4096, 4);
        CommitTicket unstarted = f.reservations.reserve(f.request(2)).ticket();
        f.plans.position = 3;
        CommitReservation.ReleaseResult rebuilt = f.reservations.revalidate(unstarted);
        assertTrue(rebuilt.released());
        assertEquals("plan-rebuilt", rebuilt.diagnostic());
        assertEquals(0, f.log.reservedSlots());
        assertEquals(0, f.store.capacitySlots());
        CommitReservation.ReserveResult again = f.reservations.reserve(f.request("a/0", 7L, 0, 2, 22L, 22L));
        assertFalse(again.admitted(), "the same node name no longer carries the frozen place");
        assertEquals("plan-ref-mismatch", again.diagnostic());
    }

    @Test
    void aRebuiltPlanDoesNotMoveTheLineATicketAlreadyReserved() {
        Fixture f = new Fixture(4, 4096, 4);
        AtomicReference<String> during = new AtomicReference<>();
        CommitController controller = new CommitController(f.reservations, ticket -> {
            f.plans.position = 3;
            during.set(f.plans.resolveCalls + "");
            return outcome(CommitTicket.Terminal.PUBLISHED, 2);
        });
        CommitController.SubmitResult result = controller.submit(f.request(2));
        assertTrue(result.admitted());
        assertEquals(during.get(), f.plans.resolveCalls + "",
            "the record of the terminal asked the plan nothing");
        f.log.closeTick();
        CommitLog.TypedStep step = f.log.lastTyped().typedSteps().get(0);
        assertEquals(7L, step.planSequence(), "the line kept the place the plan froze into the ticket");
        assertEquals(0, step.position());
    }

    @Test
    void aTerminalThatOutgrowsItsPromiseIsAFaultAndStopsTheRoom() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitTicket.ReserveRequest request = f.request(2);
        CommitTicket ticket = f.reservations.reserve(request).ticket();
        assertNotNull(ticket);
        assertTrue(f.reservations.beginPublish(ticket));
        CommitTicket.Outcome grown = new CommitTicket.Outcome(CommitTicket.Terminal.PARTIAL, "publish",
            List.of("a=1", "b=2"), 1, "a-note-nobody-promised-for");
        CommitReservation.FinalizeResult result = f.reservations.finalize(ticket, grown);

        assertFalse(result.finalized(), "a record past its bound is not written");
        assertEquals("record-refused", result.diagnostic());
        assertEquals(1L, f.store.integrityFaults());
        assertEquals("reserved-bound-exceeded", f.store.integrityReason());
        assertTrue(f.store.halted());
        assertEquals(1, f.store.unresolvedTerminals().size(),
            "the item that could not be written is still readable");
        assertEquals(1, f.reservations.outstanding(), "the reservation was not thrown away either");
        assertEquals(0, f.log.ringDepth(), "no line was written for a record that does not exist");

        CommitController controller = new CommitController(f.reservations,
            line -> outcome(CommitTicket.Terminal.PUBLISHED, 1));
        CommitController.SubmitResult after = controller.submit(f.request("a/1", 7L, 1, 2, 21L, 21L));
        assertFalse(after.admitted(), "a store that faulted hands out no more room");
        assertEquals("log-integrity-fault", after.diagnostic());
        assertEquals(0L, controller.supplierCalls(), "the publish action is not called for it");
    }

    @Test
    void theTerminalLedgerIsBoundedAndOneToOne() {
        Fixture f = new Fixture(4, 65536, 200);
        CommitController controller = new CommitController(f.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 1));
        for (int index = 0; index < 70; index++) {
            f.plans.position = index;
            assertTrue(controller.submit(f.request("a/0", 7L, index, 2, 100L + index, 500L + index))
                .admitted(), "write " + index);
            assertTrue(f.reservations.conservationHolds(), "conservation at write " + index);
            f.log.closeTick();
        }
        assertEquals(70L, f.reservations.reserveSuccess());
        assertEquals(70L, f.reservations.finalized());
        assertEquals(0, f.reservations.outstanding());
        assertEquals(0L, f.reservations.releasedCount());
        assertEquals(70L, f.reservations.unitTotal());
        assertEquals(64, f.reservations.terminalLedger().size(),
            "the ledger is bounded and the newest entries are the ones it keeps");
        assertTrue(f.reservations.ledgerEvicted() > 0L, "a ledger past its bound says how much it dropped");
        assertEquals(70L, f.log.sequence(), "one line per terminal");
    }

    /** X-03: the two non-positive world generations are named apart and are not the answer a
     * missing column gets, and neither of them reaches a ring slot, a room or the publish action. */
    @Test
    void anOldWorldGenerationIsNamedApartAndRefusedBeforeAnythingIsTaken() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitController controller = new CommitController(f.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 1));
        CommitNamespaces.Namespace own = new CommitNamespaces.Namespace("owner-a",
            f.reservations.controller().lifetime(), f.epochs.current(), 91L);

        CommitReservation.ReserveResult untracked = f.reservations.reserve(
            f.requestWith(NODE, CommitLog.NO_WORLD_EPOCH, own));
        CommitReservation.ReserveResult lost = f.reservations.reserve(
            f.requestWith(NODE, -1L, own));

        assertFalse(untracked.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, untracked.code());
        assertEquals("world-untracked", untracked.diagnostic(),
            "the zero generation is the untracked state and not a missing column");
        assertFalse(lost.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, lost.code());
        assertEquals("world-not-live", lost.diagnostic(),
            "the lost generation is a second state and not the same answer as the zero");
        assertEquals(0, f.log.reservedSlots(), "the refusal is before any ring slot");
        assertEquals(0, f.store.capacitySlots(), "and before any room");
        assertEquals(0, f.log.heldPlans());
        assertEquals(0L, controller.supplierCalls(), "and no side effect was reached");
        assertEquals(0L, f.reservations.reserveSuccess());
        assertEquals(0, f.reservations.outstanding());
        assertEquals(0L, f.log.sequence(), "no line was written for either of them");
    }

    /** X-03 / B1: a controller of another lifecycle is refused by name, in the same position and
     * still before anything is taken. */
    @Test
    void anotherLifecycleNamespaceIsRefusedByItsOwnNameBeforeAnythingIsTaken() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitController controller = new CommitController(f.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 1));
        CommitNamespaces.Namespace otherLifecycle = new CommitNamespaces.Namespace("owner-a",
            f.reservations.controller().lifetime(), f.epochs.current() + 1L, 91L);

        CommitReservation.ReserveResult refused = f.reservations.reserve(
            f.requestWith(NODE, 5L, otherLifecycle));

        assertFalse(refused.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, refused.code());
        assertEquals("controller-namespace-mismatch", refused.diagnostic());
        assertEquals(0, f.log.reservedSlots());
        assertEquals(0, f.store.capacitySlots());
        assertEquals(0L, controller.supplierCalls());
        assertEquals(0L, f.reservations.reserveSuccess());
    }

    /** X-01: a controller that declares nothing is refused by name, before the seat is even looked
     * up, and the write side holds nothing afterwards. */
    @Test
    void aLineWhoseControllerDeclaresNothingIsRefusedBeforeAnythingIsTaken() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitController controller = new CommitController(f.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 1));

        CommitReservation.ReserveResult refused = f.reservations.reserve(
            f.requestWith(NODE, 5L, CommitNamespaces.Namespace.NONE));

        assertFalse(refused.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, refused.code());
        assertEquals("identity-undeclared", refused.diagnostic());
        assertFalse(CommitNamespaces.Namespace.NONE.declared(),
            "the undeclared namespace is a named constant and not a number that reads as one");
        assertEquals(CommitNamespaces.NO_LIFETIME, CommitNamespaces.Namespace.NONE.lifetime());
        assertEquals("", CommitNamespaces.Namespace.NONE.ownerId());
        assertEquals(0, f.log.reservedSlots());
        assertEquals(0, f.store.capacitySlots());
        assertEquals(0L, controller.supplierCalls());
        assertEquals(0L, f.log.sequence());
    }

    /** P-05 禁止面的库内负例：a line with no node is refused instead of being read as a seat of
     * the pair, and the reservation that would have carried it takes nothing. */
    @Test
    void aLineWithNoNodeAtAllIsRefusedInsteadOfReadingAsASeat() {
        Fixture f = new Fixture(4, 4096, 4);
        CommitController controller = new CommitController(f.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 1));
        CommitNamespaces.Namespace own = new CommitNamespaces.Namespace("owner-a",
            f.reservations.controller().lifetime(), f.epochs.current(), 91L);

        CommitReservation.ReserveResult empty = f.reservations.reserve(f.requestWith("", 5L, own));

        assertFalse(empty.admitted());
        assertEquals(RejectCode.COUNTER_MISSING, empty.code());
        assertEquals("request-incomplete", empty.diagnostic());
        assertNull(f.log.orderSource().resolve(WORLD, DOMAIN, ""),
            "an empty node resolves to no place, so it can never be seated");
        assertEquals(0, f.log.reservedSlots());
        assertEquals(0, f.store.capacitySlots());
        assertEquals(0L, controller.supplierCalls());
        assertEquals(0L, f.reservations.reserveSuccess());
    }

    @Test
    void withNothingBoundThePlainProjectionIsTheOneItAlwaysWas() {
        Fixture f = new Fixture(2, 4096, 4);
        f.log.beginTick(1L);
        assertTrue(f.log.reach(batch("a/0")).logged());

        CommitLog.Entry entry = f.log.rings().get(0).peek();
        assertEquals(CommitLog.Identity.NONE, entry.identity(),
            "every column of the versioned face is at its declared default");
        assertEquals(0L, entry.handle());
        assertEquals(CommitLog.NO_INSTANCE, entry.identity().instanceId());
        assertEquals(CommitLog.NO_WORLD_EPOCH, entry.identity().worldEpoch());
        assertEquals(CommitLog.NO_EFFECT, entry.identity().effectRef());
        assertEquals("", entry.identity().targetStatus());
        assertEquals(1L, f.log.accepted(), "and the plain counters moved as they always did");
        assertEquals(1L, f.log.sequence());
        f.log.closeTick();
        assertEquals(CommitLog.SCHEMA_UNDECLARED, f.log.lastTyped().schemaVersion(),
            "a tick of plain reports declares no versioned protocol");
        assertEquals(1, f.log.lastTyped().plainSteps().size());
        assertTrue(f.log.lastTyped().typedSteps().isEmpty());
        assertEquals(0L, f.log.typedStored());
        assertEquals(0L, f.log.ticketStored());
        assertEquals(0L, f.log.ticketRefused());
        assertEquals(0L, f.log.hybridOrderRefusals());
        assertEquals(0, f.log.heldPlans());
        assertEquals(0, f.log.reservedSlots());
        assertEquals(0, f.store.capacitySlots());
        assertEquals(0L, f.log.crossLogRefusals());
        assertEquals(0L, f.log.staleTicketRefusals());
        assertEquals(0L, f.log.integrityFaults());
    }

    @Test
    void everyControlOperationRunsOnTheOwnerThreadAndRefusesAnother() throws Exception {
        Fixture f = new Fixture(2, 4096, 4);
        CommitController controller = new CommitController(f.reservations,
            ticket -> outcome(CommitTicket.Terminal.PUBLISHED, 1));
        assertSame(Thread.currentThread(), f.reservations.ownerThread());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<CommitReservation.ReserveResult> reserved = new AtomicReference<>();
            AtomicReference<CommitController.ResetDecision> reset = new AtomicReference<>();
            pool.submit(() -> {
                reserved.set(f.reservations.reserve(f.request(2)));
                reset.set(controller.enterReset());
            }).get(30, TimeUnit.SECONDS);

            assertFalse(reserved.get().admitted());
            assertEquals("off-owner-thread", reserved.get().diagnostic());
            assertFalse(reset.get().granted());
            assertEquals("off-owner-thread", reset.get().reason());
            assertEquals(1L, controller.resetHandOffs(), "the reset is handed off, not run elsewhere");
            assertEquals(0L, controller.resets());
            assertEquals(0L, f.epochs.current());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, f.log.reservedSlots());
        assertEquals(0, f.store.capacitySlots());
        assertEquals(0L, controller.supplierCalls());
        assertEquals("SYNTHETIC", controller.publisherWiring(), "no host supplier is bound here");
        assertNotNull(controller.reservations());
    }
}
