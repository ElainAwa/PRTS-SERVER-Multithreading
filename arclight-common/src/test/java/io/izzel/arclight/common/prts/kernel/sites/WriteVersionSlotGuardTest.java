/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the judged path does with the version of a write: with no slot, with a slot, and with a
 * slot that was advanced past the value the write names. */
class WriteVersionSlotGuardTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String SITE = "site:worker";
    private static final String DOMAIN = WritePath.BLOCK_WRITE.key();
    private static final long TICK = 100L;
    private static final Object LEVEL = new Object();

    @Test
    void aWriteWhoseDomainHoldsNoSlotIsRefusedForTheVersionItDoesNotCarry() {
        Scratch scratch = new Scratch();
        scratch.slots.refresh(true);

        scratch.probe();

        assertEquals(RejectCode.VERSION_MISMATCH, scratch.guard.lastDecision().code());
        assertEquals(WriteDisposition.DENY, scratch.guard.lastDecision().disposition());
        assertEquals(1L, scratch.slots.notCarriedCount());
    }

    @Test
    void aWriteWhoseDomainHoldsASlotCarriesItPastTheVersionGate() {
        Scratch scratch = new Scratch();
        scratch.slots.refresh(true);
        long granted = scratch.slots.grant(WORLD, WriteLevel.REGION, DOMAIN);
        assertTrue(granted > WriteVersionSlots.NOT_CARRIED);

        scratch.probe();

        assertEquals(1L, scratch.slots.carriedCount(), "the judged path asked for the version");
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, scratch.guard.lastDecision().code(),
            "the version gate is passed and the write stops at the owner gate, which no token feeds");
    }

    @Test
    void aWriteFrozenAgainstAnAdvancedSlotIsRefusedWithTheFiveReadableElements() {
        Scratch scratch = new Scratch();
        scratch.slots.refresh(true);
        long frozen = scratch.slots.grant(WORLD, WriteLevel.REGION, DOMAIN);
        scratch.owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, 1L, frozen, 0L, 0L,
            HolderKind.REGISTERED, SITE));
        long advanced = scratch.slots.advance(WORLD, WriteLevel.REGION, DOMAIN);
        assertNotEquals(frozen, advanced);

        scratch.probe();

        WorldWriteGuard.WriteDecision decision = scratch.guard.lastDecision();
        assertEquals(RejectCode.VERSION_MISMATCH, decision.code());
        assertEquals(WritePath.BLOCK_WRITE, decision.path());
        assertEquals(SITE, decision.siteId());
        assertEquals("worker-under-test", decision.threadRef());
        assertEquals(WORLD, decision.worldId());
        assertEquals(TICK, decision.tickIndex());
    }

    @Test
    void aSlotSourceThatIsOffLeavesEveryWriteWithoutAVersion() {
        Scratch scratch = new Scratch();
        scratch.slots.grant(WORLD, WriteLevel.REGION, DOMAIN);

        scratch.probe();
        scratch.probe();

        assertEquals(RejectCode.VERSION_MISMATCH, scratch.guard.lastDecision().code());
        assertEquals(0, scratch.slots.activeSlots());
        assertEquals(0L, scratch.slots.grantedCount());
        assertEquals(0L, scratch.slots.carriedCount());
    }

    @Test
    void theShortPathReturnsTheSameSequenceWithASlotSourceWiredAndWithout() {
        Scratch unwired = new Scratch();
        Scratch wired = new Scratch();
        wired.slots.refresh(true);
        wired.slots.grant(WORLD, WriteLevel.REGION, DOMAIN);

        assertEquals(List.of(0, 0, 1, 0, 1), unwired.shortPathSequence());
        assertEquals(unwired.shortPathSequence(), wired.shortPathSequence());
    }

    @Test
    void theReadoutPublishesTheFiveElementsOfARejection() {
        List<String> export = KernelReadings.export(KernelModule.instance());

        assertTrue(export.stream().anyMatch(line -> line.startsWith("write.last_decision.code=")));
        assertTrue(export.stream().anyMatch(line -> line.startsWith("write.last_decision.site=")));
        assertTrue(export.stream().anyMatch(line -> line.startsWith("write.last_decision.thread=")));
        assertTrue(export.stream().anyMatch(line -> line.startsWith("write.last_decision.world=")));
        assertTrue(export.stream().anyMatch(line -> line.startsWith("write.last_decision.tick=")));
        assertTrue(export.stream().anyMatch(line -> line.startsWith("write.version_slots.enabled=")));
    }

    /** The write paths of one process, wired the way the kernel wires them. */
    private static final class Scratch {

        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger ledger = new WriteLedger();
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final OwnerRegistry owners = new OwnerRegistry();
        private final IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        private final CommitSegment segment = new CommitSegment(intents, () -> true, intents::capacity);
        private final WriteAuthority authority =
            new WriteAuthority(owners, intents, ledger, () -> false, () -> 2);
        private final WorldWriteGuard guard =
            new WorldWriteGuard(counters, authority, intents, payloads, ledger);
        private final WriteVersionSlots slots = new WriteVersionSlots();

        private Scratch() {
            segment.bindOwnerThread(Thread.currentThread());
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), "host:server-thread");
            guard.versionSlots(slots);
            guard.refresh(true, false, false, TICK);
        }

        /** One write of a declared holder, made on the thread that holder was declared for. */
        private void probe() {
            Thread thread = new Thread(() -> guard.admitBlockWrite(LEVEL, WORLD, null),
                "worker-under-test");
            guard.registerHolder(thread, HolderKind.REGISTERED, SITE);
            run(thread);
        }

        /** The values the short path answers over the states a tick can be in. */
        private List<Integer> shortPathSequence() {
            guard.refresh(false, false, false, TICK);
            int inactive = guard.classifyBlockWrite(LEVEL);
            guard.refresh(true, false, false, TICK);
            int onTheServerThread = guard.classifyBlockWrite(LEVEL);
            int onAnotherThread = otherThreadClassification();
            guard.refresh(false, false, false, TICK);
            int inactiveAgain = guard.classifyBlockWrite(LEVEL);
            guard.refresh(true, false, false, TICK);
            return List.of(inactive, onTheServerThread, onAnotherThread, inactiveAgain,
                otherThreadClassification());
        }

        private int otherThreadClassification() {
            int[] answer = new int[1];
            Thread thread = new Thread(() -> answer[0] = guard.classifyBlockWrite(LEVEL),
                "another-thread");
            run(thread);
            return answer[0];
        }

        private void run(Thread thread) {
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }
}
