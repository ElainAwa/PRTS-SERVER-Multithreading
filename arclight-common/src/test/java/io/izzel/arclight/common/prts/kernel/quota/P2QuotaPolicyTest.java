/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.quota;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure contract: non-cartesian family/level matrix, RUN/DEFER/DECLINE, exact receipts and
 * token/epoch replay rules. It performs no action and touches no product readout. */
class P2QuotaPolicyTest {

    private static final QuotaPolicy.Scope WORLD = QuotaPolicy.Scope.world("minecraft:overworld");
    private static final QuotaPolicy.Scope GLOBAL = QuotaPolicy.Scope.global("server");

    @AfterEach
    void cleanup() {
        QuotaPolicy.reset();
    }

    private static QuotaPolicy.Request request(QuotaPolicy.Family family, DegradeLevel level,
                                               QuotaPolicy.Scope scope, boolean safe,
                                               boolean deferable) {
        return new QuotaPolicy.Request(family, level, scope, QuotaPolicy.epoch(scope.id()), 7L,
            12.0, 4.0, safe, deferable);
    }

    @Test
    void deviceEntityOnlyMapToL3AndRunWithToken() {
        QuotaPolicy.Receipt device = QuotaPolicy.decide(request(QuotaPolicy.Family.DEVICE,
            DegradeLevel.B3, WORLD, true, false));
        assertEquals(QuotaPolicy.Decision.RUN, device.decision());
        assertEquals(QuotaPolicy.Result.OK, device.result());
        assertTrue(device.token() > 0L);
        assertEquals("act", device.action());

        QuotaPolicy.Receipt entity = QuotaPolicy.decide(request(QuotaPolicy.Family.ENTITY,
            DegradeLevel.B3, WORLD, true, true));
        assertEquals(QuotaPolicy.Decision.DECLINE, entity.decision());
        assertEquals("defer-not-implemented", entity.action());
        assertEquals(0L, entity.token());
    }

    @Test
    void levelOutsideTheFamilyIsDeclined() {
        QuotaPolicy.Receipt deviceL4 = QuotaPolicy.decide(request(QuotaPolicy.Family.DEVICE,
            DegradeLevel.B4, WORLD, true, false));
        assertEquals(QuotaPolicy.Decision.DECLINE, deviceL4.decision());
        assertEquals(0L, deviceL4.token());
        QuotaPolicy.Receipt machine = QuotaPolicy.decide(request(QuotaPolicy.Family.MACHINE,
            DegradeLevel.B3, WORLD, true, false));
        assertEquals(QuotaPolicy.Decision.DECLINE, machine.decision());
        assertEquals("machine-no-safe-action", machine.action());
    }

    @Test
    void netIsDeclineOnlyAndNeedsGlobalScope() {
        QuotaPolicy.Receipt net = QuotaPolicy.decide(request(QuotaPolicy.Family.NET,
            DegradeLevel.B3, GLOBAL, true, false));
        assertEquals(QuotaPolicy.Decision.DECLINE, net.decision());
        assertEquals(0L, net.token());
        QuotaPolicy.Receipt nonGlobal = QuotaPolicy.decide(request(QuotaPolicy.Family.NET,
            DegradeLevel.B3, WORLD, true, false));
        assertEquals(QuotaPolicy.Decision.DECLINE, nonGlobal.decision());
        QuotaPolicy.Receipt netDefer = QuotaPolicy.decide(request(QuotaPolicy.Family.NET,
            DegradeLevel.B3, GLOBAL, true, true));
        assertEquals(QuotaPolicy.Decision.DECLINE, netDefer.decision());
        QuotaPolicy.Request quietNet = new QuotaPolicy.Request(QuotaPolicy.Family.NET,
            DegradeLevel.B3, GLOBAL, 0L, 7L, 2.0, 4.0, true, true);
        QuotaPolicy.Receipt quiet = QuotaPolicy.decide(quietNet);
        assertEquals(QuotaPolicy.Decision.RUN, quiet.decision());
        assertEquals(QuotaPolicy.Result.NOOP, quiet.result());
        assertEquals(0L, quiet.token());
    }

    @Test
    void noSafeActionAndNoOverrunAreNotActions() {
        QuotaPolicy.Receipt noSafe = QuotaPolicy.decide(request(QuotaPolicy.Family.DEVICE,
            DegradeLevel.B3, WORLD, false, false));
        assertEquals(QuotaPolicy.Decision.DECLINE, noSafe.decision());
        assertEquals(0L, noSafe.token());

        QuotaPolicy.Request under = new QuotaPolicy.Request(QuotaPolicy.Family.ENTITY,
            DegradeLevel.B3, WORLD, 0L, 7L, 2.0, 4.0, true, true);
        QuotaPolicy.Receipt noOver = QuotaPolicy.decide(under);
        assertEquals(QuotaPolicy.Decision.RUN, noOver.decision());
        assertEquals(QuotaPolicy.Result.NOOP, noOver.result());
        assertEquals(0L, noOver.token());
    }

    @Test
    void receiptCarriesEveryContractedField() {
        QuotaPolicy.Receipt receipt = QuotaPolicy.decide(request(QuotaPolicy.Family.DEVICE,
            DegradeLevel.B3, WORLD, true, false));
        assertEquals(QuotaPolicy.Family.DEVICE, receipt.family());
        assertEquals(WORLD, receipt.scope());
        assertEquals(0L, receipt.epoch());
        assertEquals(DegradeLevel.B3, receipt.level());
        assertTrue(receipt.token() > 0L);
        assertEquals(7L, receipt.tick());
        assertEquals(12.0, receipt.measured());
        assertEquals(4.0, receipt.limit());
        assertEquals("epoch", receipt.rollback());
        assertEquals("act", receipt.action());
    }

    @Test
    void replayIsIdempotentAndEpochRetirementFailsIt() {
        QuotaPolicy.Receipt first = QuotaPolicy.decide(request(QuotaPolicy.Family.DEVICE,
            DegradeLevel.B3, WORLD, true, false));
        QuotaPolicy.Receipt replay = QuotaPolicy.replay(WORLD, 0L, first.token());
        assertSame(first, replay);
        QuotaPolicy.Receipt wrongScope = QuotaPolicy.replay(GLOBAL, 0L, first.token());
        assertEquals(QuotaPolicy.Result.FAILED, wrongScope.result());
        QuotaPolicy.Receipt wrongEpoch = QuotaPolicy.replay(WORLD, 1L, first.token());
        assertEquals(QuotaPolicy.Result.FAILED, wrongEpoch.result());

        assertEquals(1L, QuotaPolicy.retireEpoch(WORLD.id()));
        QuotaPolicy.Receipt retired = QuotaPolicy.replay(WORLD, 1L, first.token());
        assertEquals(QuotaPolicy.Result.FAILED, retired.result());
    }

    @Test
    void machineNeverCancelsNeighborUpdatesAndB4IsAbsent() {
        QuotaPolicy.Receipt machine = QuotaPolicy.decide(request(QuotaPolicy.Family.MACHINE,
            DegradeLevel.B3, WORLD, true, true));
        assertEquals(QuotaPolicy.Decision.DECLINE, machine.decision());
        assertEquals("machine-no-safe-action", machine.action());
        for (QuotaPolicy.Family family : QuotaPolicy.Family.values()) {
            assertNotEquals("B4", family.name());
        }
    }

    @Test
    void shareTableWeightsStayUnchanged() {
        double sum = 0.0;
        for (ShareClass shareClass : ShareClass.values()) {
            sum += shareClass.weight();
        }
        assertEquals(1.0, sum, 1.0e-9);
        assertEquals(0.30, ShareClass.ENTITY.weight(), 1.0e-9);
        assertEquals(0.20, ShareClass.BLOCKENTITY.weight(), 1.0e-9);
        assertEquals(0.24, ShareClass.AI.weight(), 1.0e-9);
    }

    @Test
    void repeatedReplayIsTheSameReceiptAndCapacityIsBounded() {
        QuotaPolicy.Receipt first = QuotaPolicy.decide(request(QuotaPolicy.Family.DEVICE,
            DegradeLevel.B3, WORLD, true, false));
        assertSame(first, QuotaPolicy.replay(WORLD, 0L, first.token()));
        assertSame(first, QuotaPolicy.replay(WORLD, 0L, first.token()));
        for (int index = 0; index < 4100; index++) {
            QuotaPolicy.decide(request(QuotaPolicy.Family.DEVICE, DegradeLevel.B3, WORLD, true, false));
        }
        assertEquals(QuotaPolicy.Result.FAILED,
            QuotaPolicy.replay(WORLD, 0L, first.token()).result());
    }

    @Test
    void unknownAndInvalidRequestsAreFailedWithoutTokens() {
        QuotaPolicy.Receipt unknown = QuotaPolicy.replay(WORLD, 0L, 999L);
        assertEquals(QuotaPolicy.Result.FAILED, unknown.result());
        assertNull(unknown.family());
        QuotaPolicy.Receipt invalid = QuotaPolicy.decide(null);
        assertEquals(QuotaPolicy.Result.FAILED, invalid.result());
        assertNotEquals(QuotaPolicy.Result.OK, invalid.result());
    }
}
