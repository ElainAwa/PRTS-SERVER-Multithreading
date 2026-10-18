/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reservation taken for a line whose seat published a range: the seal is the admission, the
 * instance the line wears is the range's, and a missing cell seal refuses the reservation instead of
 * reading the entry path of the same seat. */
class CommitReservationRangeTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final String NODE = "a/0";
    private static final long EFFECT = 100L;
    private static final long EPOCH = 5L;

    private static final class Plans implements CommitLog.OrderSource {

        long plan = 9L;
        int position = 0;

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            if (!WORLD.equals(worldId) || !DOMAIN.equals(domainId) || !NODE.equals(nodeKey)) {
                return null;
            }
            return new CommitLog.Resolved(plan, position, false);
        }
    }

    private static final class Fixture {

        final Plans plans = new Plans();
        final EffectStore store = new EffectStore(4096, 8);
        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final RangeSealTable table = new RangeSealTable(16);
        final AtomicReference<FrozenDeclarations.FreezeKey> freeze = new AtomicReference<>();
        final CommitLog log;
        final CommitReservation reservations;

        Fixture() {
            log = new CommitLog(() -> 8, plans, store, new RangeSource(table,
                FrozenDeclarations.none(), () -> freeze.get(), EPOCH, plans.plan));
            reservations = new CommitReservation(log, store, plans, "owner-a", epochs);
            freeze.set(new FrozenDeclarations.FreezeKey(reservations.controller().lifecycleKey(),
                1L));
        }

        FrozenDeclarations.RangeSeal seal(long cell, String status, long instanceId) {
            RangeSealFixture fixture = new RangeSealFixture();
            fixture.world = WORLD;
            fixture.domain = DOMAIN;
            fixture.nodeKey = NODE;
            fixture.freeze(freeze.get());
            fixture.cells(cell).cell(cell).status(status).instance(instanceId).epoch(EPOCH)
                .at(9L, 0);
            FrozenDeclarations.RangeSeal seal = fixture.build();
            assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));
            return seal;
        }

        CommitTicket.ReserveRequest request(FrozenDeclarations.RangeSeal seal, long cell,
                                            long instanceId) {
            CommitNamespaces.Namespace producer = new CommitNamespaces.Namespace("producer", 4242L,
                0L, 77L);
            CommitNamespaces.Namespace controller = reservations.controller();
            FrozenDeclarations.CellStatus status = seal.statusFor(cell);
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instanceId,
                EPOCH, cell, status == null ? "full" : status.targetStatus(), plans.plan, EFFECT,
                producer, controller);
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY,
                seal.planSequence(), seal.seat().position(), plans.plan, 4242L, 1, identity,
                controller, effect(EFFECT, "publish", "a=1"), effect(EFFECT, "publish", "p=1"),
                effect(EFFECT, "publish", "e=1"));
        }

        CommitTicket.ReserveRequest request(FrozenDeclarations.RangeSeal seal) {
            return request(seal, seal.statuses().keySet().iterator().next(), seal.instanceId());
        }
    }

    private static EffectStore.Effect effect(long ref, String operation, String row) {
        return new EffectStore.Effect(ref, operation, "declared", List.of(row));
    }

    private static CommitTicket.Outcome outcome(CommitTicket.Terminal terminal, int units) {
        return new CommitTicket.Outcome(terminal, "publish", List.of("a=1"), units, "");
    }

    @Test
    void aRangeReservationLandsAndItsTerminalKeepsTheSealAttribution() {
        Fixture f = new Fixture();
        FrozenDeclarations.RangeSeal seal = f.seal(64L, "full", 41L);

        CommitReservation.ReserveResult reserved = f.reservations.reserve(f.request(seal));

        assertTrue(reserved.admitted(), String.valueOf(reserved.diagnostic()));
        assertNotNull(reserved.ticket());
        assertEquals(1, f.table.pinnedCount(), "the reservation holds the seal it was judged by");
        assertTrue(f.reservations.beginPublish(reserved.ticket()));
        CommitReservation.FinalizeResult result = f.reservations.finalize(reserved.ticket(),
            outcome(CommitTicket.Terminal.PUBLISHED, 1));

        assertTrue(result.finalized());
        assertEquals(1L, f.log.accepted());
        assertEquals(1L, f.log.ticketStored());
        assertEquals(0L, f.log.identityCollisions());
        assertEquals(1L, f.log.sequence());
        assertEquals(1, f.log.unreadObligations(), "the terminal line owes the range seal");
        assertEquals(1, f.table.pinnedCount());
        assertNotNull(f.table.lookup(seal.freeze(), seal.seat(), 64L));

        f.log.closeTick();
        assertEquals(0, f.log.unreadObligations());
        assertEquals(0, f.table.pinnedCount(), "the read line settled the obligation");
        assertTrue(f.table.release(seal));
        assertEquals(0, f.table.size());
    }

    @Test
    void aGivenBackRangeReservationSettlesItsObligationAndLeavesNoPin() {
        Fixture f = new Fixture();
        FrozenDeclarations.RangeSeal seal = f.seal(64L, "full", 41L);
        CommitTicket ticket = f.reservations.reserve(f.request(seal)).ticket();
        assertNotNull(ticket);
        assertEquals(1, f.table.pinnedCount());

        CommitReservation.ReleaseResult released = f.reservations.release(ticket,
            CommitTicket.ReleaseReason.CANCELLED);

        assertTrue(released.released());
        assertEquals(0, f.table.pinnedCount(), "the reservation's obligation is settled once");
        assertEquals(1, f.table.size(), "no window released the seal, so the table keeps it");
        assertTrue(f.table.release(seal));
        assertEquals(0, f.table.size());
        assertFalse(f.reservations.release(ticket, CommitTicket.ReleaseReason.CANCELLED).released(),
            "a ticket is given back once");
    }

    @Test
    void aReservationOfASeatWhoseCellHasNoSealIsRefused() {
        Fixture f = new Fixture();
        FrozenDeclarations.RangeSeal seal = f.seal(64L, "full", 41L);
        assertTrue(seal.wellFormed());

        CommitReservation.ReserveResult result = f.reservations.reserve(f.request(seal, 65L, 41L));

        assertFalse(result.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, result.code());
        assertEquals("range-seal-missing", result.diagnostic());
        assertEquals(0, f.reservations.outstanding());
        assertEquals(0, f.log.reservedSlots());
        assertEquals(0, f.store.capacitySlots());
        assertEquals(1, f.table.size());
    }

    @Test
    void anEntryShapedRequestOfARangeSeatIsRefusedInsteadOfAdmittedByTheOlderTable() {
        Fixture f = new Fixture();
        FrozenDeclarations.RangeSeal seal = f.seal(64L, "full", 41L);
        CommitTicket.ReserveRequest shaped = f.request(seal, 65L, 777L);

        CommitReservation.ReserveResult result = f.reservations.reserve(shaped);

        assertFalse(result.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, result.code());
        assertEquals("range-seal-missing", result.diagnostic());
        assertEquals(0, f.reservations.outstanding());
        assertEquals(1, f.table.size());
    }

    @Test
    void oneInstanceNumberUnderAnotherRangeIsRefusedBeforeASlotIsTaken() {
        Fixture f = new Fixture();
        FrozenDeclarations.RangeSeal first = f.seal(64L, "full", 41L);
        CommitTicket firstTicket = f.reservations.reserve(f.request(first)).ticket();
        assertNotNull(firstTicket);
        assertTrue(f.reservations.beginPublish(firstTicket));
        assertTrue(f.reservations.finalize(firstTicket, outcome(CommitTicket.Terminal.PUBLISHED, 1))
            .finalized());
        assertEquals(0L, f.log.identityCollisions());
        FrozenDeclarations.RangeSeal second = f.seal(65L, "features", 41L);

        CommitReservation.ReserveResult collision = f.reservations.reserve(f.request(second));

        assertFalse(collision.admitted());
        assertEquals(RejectCode.VERSION_MISMATCH, collision.code());
        assertEquals("instance-collision", collision.diagnostic());
        assertEquals(0, f.reservations.outstanding());
        assertEquals(2, f.table.size(), "both seals are where they were");
    }
}
