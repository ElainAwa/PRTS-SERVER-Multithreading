/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.IdentityWitness;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealFixture;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.commit.ReplayComparator;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The identity source of the real module answers the range table the module wires: the real module
 * singleton, the real log it hands out and the real publish table. The entry path and the four-part
 * replay evidence are read on the same objects to show they are unchanged. */
class RangeIdentitySourceWiringTest {

    private KernelModule module;

    @BeforeEach
    void resetTheModule() {
        module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.rollbackCommitChain();
        module.markSafePoint();
        module.resetReadings();
        module.markSafePoint();
        assertEquals(0, module.rangeSeals().size(), "the range table starts empty for the case");
    }

    @AfterEach
    void leaveTheTableEmpty() {
        module.resetReadings();
        module.markSafePoint();
        assertFalse(module.commitResetPending(), "no reset request outlives the case");
    }

    @Test
    void theProductionTableIsSizedByTheLedgerAndResetWithTheReadings() {
        assertEquals(module.pipelineBatches().pendingLimit(), module.rangeSeals().capacity(),
            "the table capacity is the pending bound of the one ledger");
        assertEquals(PipelineBatchLedger.DEFAULT_PENDING_LIMIT, module.rangeSeals().capacity());
        long generation = module.rangeSeals().generation();
        RangeSealFixture fixture = new RangeSealFixture();
        assertEquals(RangeSealTable.Publish.PUBLISHED, module.rangeSeals().publish(fixture.build()));
        assertEquals(1, module.rangeSeals().size());

        module.resetReadings();

        assertEquals(0, module.rangeSeals().size(), "the readings reset clears the range table");
        assertEquals(generation + 1L, module.rangeSeals().generation(),
            "the reset starts another generation, so an old window cannot publish into the new one");
    }

    @Test
    void theRealLogSourceAnswersTheTableTheModuleWires() {
        RangeSealFixture fixture = new RangeSealFixture();
        FrozenDeclarations.Seat seat = fixture.seat();
        FrozenDeclarations.RangeSeal seal = fixture.build();
        assertEquals(RangeSealTable.Publish.PUBLISHED, module.rangeSeals().publish(seal));
        IdentityWitness.Source source = module.commits().identitySource();

        assertEquals(seal, source.range(seat, fixture.cell));
        assertTrue(source.hasRange(seat));
        FrozenDeclarations.Seat otherSeat = new FrozenDeclarations.Seat(fixture.world,
            fixture.domain, fixture.planSequence, fixture.position + 1);
        assertFalse(source.hasRange(otherSeat));
        assertNull(source.range(otherSeat, fixture.cell));

        IdentityWitness.Obligation obligation = source.oblige(seal);
        assertSame(seal, module.rangeSeals().locate(seat, fixture.cell));
        assertEquals(1, module.rangeSeals().pinnedCount());
        module.rangeSeals().release(seal);
        assertSame(seal, module.rangeSeals().locate(seat, fixture.cell),
            "a released seal a line still holds stays readable until the line settles");
        obligation.settle();
        assertNull(module.rangeSeals().locate(seat, fixture.cell));
        assertFalse(source.hasRange(seat));
        obligation.settle();
        assertFalse(source.hasRange(seat), "settling twice changes nothing");
    }

    @Test
    void twoSealsOfOneSeatAreAmbiguousAndAResetForgetsBoth() {
        RangeSealFixture first = new RangeSealFixture();
        RangeSealFixture second = new RangeSealFixture();
        second.freeze(new FrozenDeclarations.FreezeKey("host/2/0", 2L));
        assertEquals(RangeSealTable.Publish.PUBLISHED, module.rangeSeals().publish(first.build()));
        assertEquals(RangeSealTable.Publish.PUBLISHED, module.rangeSeals().publish(second.build()));
        IdentityWitness.Source source = module.commits().identitySource();

        assertNull(source.range(first.seat(), first.cell),
            "two different seals under one seat and cell are ambiguous, never guessed");
        assertTrue(source.hasRange(first.seat()),
            "the seat still answers that it carries a seal, so the line is refused, not read as an entry");

        module.resetReadings();

        assertNull(source.range(first.seat(), first.cell));
        assertFalse(source.hasRange(first.seat()));
        assertEquals(0, module.rangeSeals().size());
    }

    @Test
    void theEntryPathAndTheFourPartEvidenceAreUnchanged() {
        String golden = "I17:world-a14:chunk-pipeline3:a/0#2;#64;4:full";
        IdentityWitness.LogicalKey key = new IdentityWitness.LogicalKey("world-a", "chunk-pipeline",
            "a/0", 2, 64L, "full");
        assertEquals(golden, key.canonical());
        assertFalse(key.ranged());
        IdentityWitness.LogicalKey parsed = IdentityWitness.parse(golden);
        assertNotNull(parsed);
        assertEquals(key, parsed);
        assertEquals(golden, parsed.canonical());

        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat("world-a", "chunk-pipeline", 7L, 4);
        FrozenDeclarations.Entry entry = new FrozenDeclarations.Entry(seat, "a/0", 2, 64L, "full", 41L,
            5L);
        assertEquals(golden, CommitLog.logicalKeyOf(entry).canonical());
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.of(
            new FrozenDeclarations.FreezeKey("host/1/0", 1L), 7L, List.of(entry));
        assertTrue(sealed.sealed());

        CommitLog.Entry.Step step = new CommitLog.Entry.Step(7L, 4, 64L);
        CommitLog.SchemaReplay replay = new CommitLog.SchemaReplay(CommitLog.SCHEMA_LEGACY,
            List.of(step), List.of(), 0);
        ReplayComparator.Evidence left = new ReplayComparator.Evidence("commit-1",
            ReplayComparator.recordsDigest(replay), sealed.declarations(), replay);
        ReplayComparator.Evidence right = new ReplayComparator.Evidence("commit-2",
            ReplayComparator.recordsDigest(replay), sealed.declarations(), replay);
        assertNull(left.ranges(), "the four-part evidence carries no range closure");
        ReplayComparator.Verdict verdict = new ReplayComparator().compare(left, right);
        assertTrue(verdict.equal(), "the four-part evidence still compares an entry run");
        assertNotNull(module.commits(), "the real log the module hands out");
    }
}
