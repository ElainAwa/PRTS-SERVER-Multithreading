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
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Decision;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Refusal;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Slot;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.SlotKey;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Verdict;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WriteRefusal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claim table and the one judgement the short path gained: a covered write whose contract
 * holds is captured and handed to the judged path, a covered write whose contract fails is refused,
 * and a write no slot covers keeps the path it always had. The guard, the authority, the owner
 * registry and the version slots are the production classes; only the slot table and the claim
 * source are fixtures. */
class WriteControlledSlotsTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER_WORLD = "minecraft:the_nether";
    private static final String SITE = "host:server-thread";
    private static final String OTHER_SITE = "domain:second-holder";
    private static final String DOMAIN = WritePath.BLOCK_WRITE.key();
    private static final String SEGMENT = "r.0.0";
    private static final String NODE_KEY = DOMAIN + "/" + WORLD + "/" + SEGMENT;
    private static final long TICK = 100L;
    private static final long PLAN_SEQUENCE = 7L;
    private static final int POSITION = 3;
    private static final long GENERATION = 1L;
    private static final Object LEVEL = new Object();
    private static final Object OTHER_LEVEL = new Object();

    @Test
    void aCoveredWriteWhoseContractHoldsIsCapturedAndReachesTheJudgedPath() {
        Scratch scratch = new Scratch();
        Slot slot = scratch.seatSyntheticSlot();

        assertEquals(1, scratch.guard.classifyBlockWrite(LEVEL), "a claimed write leaves the short path");

        Decision decision = scratch.slots.lastDecision();
        assertEquals(Verdict.CAPTURED, decision.verdict());
        assertEquals(NODE_KEY, decision.planNodeKey());
        assertEquals(PLAN_SEQUENCE, decision.planSequence());
        assertEquals(POSITION, decision.position());
        assertEquals(slot.writeSetDigest(), decision.writeSetDigest());
        assertFalse(decision.writeSetDigest().isEmpty());

        assertTrue(scratch.guard.admitBlockWrite(LEVEL, WORLD, null),
            "the existing judged path takes the captured write");
        assertEquals(WriteDisposition.GRANT, scratch.guard.lastDecision().disposition(),
            () -> "code=" + scratch.guard.lastDecision().code());
        assertEquals(1L, scratch.slots.capturedCount());
        assertEquals(1L, scratch.slots.attemptsCount());
        assertEquals(0L, scratch.slots.defaultPathCount());
        assertEquals(0L, scratch.slots.rejectedCount());
        assertTrue(scratch.slots.conservationHolds());
        assertEquals(1L, scratch.counters.attempts(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN,
            HolderKind.REGISTERED), "the captured write is counted once, on the judged path");
        assertTrue(scratch.counters.closureHolds());
    }

    @Test
    void aWriteNoSlotCoversStaysOnTheDefaultPath() {
        Scratch scratch = new Scratch();
        scratch.seatSyntheticSlot();

        assertEquals(0, scratch.guard.classifyBlockWrite(OTHER_LEVEL),
            "a slot for one level does not cover another level");
        assertEquals(1, scratch.guard.classifyBlockWrite(LEVEL), "the same level is covered again");

        assertEquals(1L, scratch.slots.defaultPathCount(), "only the uncovered write is a default");
        assertEquals(1L, scratch.slots.capturedCount(),
            () -> "decision=" + scratch.slots.lastDecision() + " refusal=" + scratch.slots.lastRefusal());
        assertEquals(0L, scratch.slots.rejectedCount(),
            () -> "refusal=" + scratch.slots.lastRefusal());
        assertEquals(2L, scratch.slots.attemptsCount());
        assertTrue(scratch.slots.conservationHolds());
        assertEquals(1L, scratch.counters.attempts(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN,
            HolderKind.REGISTERED), "the default-path write is the one counted on the short path");
    }

    @Test
    void aWriteOutsideTheDeclaredWriteSetOfTheOnlySlotStaysOnTheDefaultPath() {
        Scratch scratch = new Scratch();
        scratch.seatSyntheticSlot();
        WriteControlledSlots table = scratch.slots;

        assertEquals(Verdict.DEFAULT_PATH, table.judge(LEVEL, "kernel_commit", TICK).verdict());
        assertEquals(0L, table.rejectedCount());
        assertEquals(1L, table.defaultPathCount());
    }

    @Test
    void noSlotAtAllIsTheDefaultPathAndNotARefusal() {
        Scratch scratch = new Scratch();

        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.lastDecision().verdict());
        assertEquals(0L, scratch.slots.rejectedCount(), "a write without a claim is never refused");
        assertEquals(1L, scratch.slots.defaultPathCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aClaimTableThatIsOffLeavesEveryWriteOnTheDefaultPath() {
        Scratch scratch = new Scratch();
        scratch.slots.register(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));

        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(2L, scratch.slots.defaultPathCount());
        assertEquals(0L, scratch.slots.capturedCount());
        assertEquals(0L, scratch.slots.rejectedCount());
    }

    @Test
    void aWriteWhoseDomainHoldsNoVersionIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(WriteVersionSlots.NOT_CARRIED, TICK + 40L, NODE_KEY),
            WriteVersionSlots.NOT_CARRIED, 0L);

        assertRefused(scratch, "version", WriteVersionSlots.NOT_CARRIED);
    }

    @Test
    void aSlotWhoseExpiryTickHasPassedIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK, NODE_KEY));

        assertRefused(scratch, "expiry", 1L);
    }

    @Test
    void aWriteOfAnotherWorldThanTheSlotNamesIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));
        scratch.source.world = OTHER_WORLD;

        assertRefused(scratch, "world", 1L);
    }

    @Test
    void aSecondHolderOfTheSameDomainIsRefused() {
        Scratch scratch = new Scratch();
        long version = scratch.version();
        scratch.seat(scratch.syntheticSlotForHolder(OTHER_SITE, version));
        assertFalse(scratch.owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, 2L,
            version, 0L, 0L, HolderKind.REGISTERED, OTHER_SITE)), "the first holder keeps the domain");
        assertEquals(1L, scratch.owners.doubleHolderCount());

        assertRefused(scratch, "owner", version);
    }

    @Test
    void aCoveredWriteWhosePlanNodeIsMissingIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));
        scratch.source.planHolds = false;

        assertRefused(scratch, "plan", 1L);
    }

    @Test
    void aCoveredWriteWhosePlanStepIsOfAnotherSequenceIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));
        scratch.source.step = new WriteControlledSlots.Source.Step(PLAN_SEQUENCE + 1L, POSITION);

        assertRefused(scratch, "plan_sequence", 1L);
    }

    @Test
    void aWriteCoveredByARetiredGenerationIsRefused() {
        Scratch scratch = new Scratch();
        Slot slot = scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY);
        scratch.seat(slot);
        assertNotEquals(GENERATION, scratch.slots.advance(slot.key()));

        assertRefused(scratch, "generation", 1L);
    }

    @Test
    void aWriteCoveredWhileTheWorldEpochMovedOnIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));
        scratch.guard.noteLiveWorlds(List.of(OTHER_WORLD));

        assertRefused(scratch, "world_epoch", 1L);
    }

    @Test
    void aSlotThatCannotBeJudgedIsRefusedWhenItIsDeclared() {
        Scratch scratch = new Scratch();
        long version = scratch.version();

        assertEquals(Refusal.WILDCARD_KEY, scratch.slots.register(
            scratch.syntheticSlotWith("*", DOMAIN, SEGMENT, PLAN_SEQUENCE, version, Set.of(DOMAIN))));
        assertEquals(Refusal.WILDCARD_KEY, scratch.slots.register(
            scratch.syntheticSlotWith(WORLD, "*", SEGMENT, PLAN_SEQUENCE, version, Set.of(DOMAIN))));
        assertEquals(Refusal.EMPTY_KEY, scratch.slots.register(
            scratch.syntheticSlotWith(WORLD, DOMAIN, "", PLAN_SEQUENCE, version, Set.of(DOMAIN))));
        assertEquals(Refusal.EMPTY_WRITE_SET, scratch.slots.register(
            scratch.syntheticSlotWith(WORLD, DOMAIN, SEGMENT, PLAN_SEQUENCE, version, Set.of())));
        assertEquals(Refusal.PLAN_SEQUENCE_INVALID, scratch.slots.register(
            scratch.syntheticSlotWith(WORLD, DOMAIN, SEGMENT, 0L, version, Set.of(DOMAIN))));
        assertEquals(0, scratch.slots.slots(), "no refused slot was seated");
        assertEquals(2L, scratch.slots.refusedCount(Refusal.WILDCARD_KEY));
        assertEquals(1L, scratch.slots.refusedCount(Refusal.EMPTY_KEY));
        assertEquals(1L, scratch.slots.refusedCount(Refusal.EMPTY_WRITE_SET));
        assertEquals(1L, scratch.slots.refusedCount(Refusal.PLAN_SEQUENCE_INVALID));
        assertEquals(5L, scratch.slots.refusedCount());
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL), "the write keeps the default path");
        assertEquals(0L, scratch.slots.rejectedCount());
    }

    @Test
    void aSecondSlotOfTheSameGenerationIsRefused() {
        Scratch scratch = new Scratch();
        Slot slot = scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY);

        assertEquals(Refusal.NONE, scratch.slots.register(slot));
        assertEquals(Refusal.DUPLICATE_KEY, scratch.slots.register(slot));
        assertEquals(1, scratch.slots.slots());
        assertEquals(1L, scratch.slots.refusedCount(Refusal.DUPLICATE_KEY));
    }

    @Test
    void anInputWithoutALevelOrAWritePathIsNotExecutable() {
        Scratch scratch = new Scratch();
        scratch.seatSyntheticSlot();

        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.judge(null, DOMAIN, TICK).verdict());
        assertEquals("NOT-EXECUTABLE", scratch.slots.lastDecision().reason());
        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.judge(LEVEL, "", TICK).verdict());
        assertEquals(2L, scratch.slots.notExecutableCount());
        assertEquals(0L, scratch.slots.attemptsCount(), "nothing was judged, so nothing is conserved");
        assertEquals(0L, scratch.slots.rejectedCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void theShortPathKeepsTheSequenceOfTheRevisionBeforeTheClaimTable() {
        Scratch bare = new Scratch();
        bare.guard.controlledSlots(null);
        Scratch off = new Scratch();
        off.slots.register(off.syntheticSlot(off.version(), TICK + 40L, NODE_KEY));
        Scratch open = new Scratch();
        open.slots.refresh(true);
        Scratch wired = new Scratch();
        wired.seatSyntheticSlot();

        List<Integer> parent = parentSequence(bare);
        assertEquals(List.of(0, 0, 1, 0, 1), parent, "the sequence of the revision before this change");
        assertEquals(parent, bare.shortPathSequence(), "no claim table wired");
        assertEquals(parent, off.shortPathSequence(), "claim table wired, every write uncovered");
        assertEquals(parent, open.shortPathSequence(), "claim table on, no slot declared");
        assertEquals(List.of(0, 1, 1, 0, 1), wired.shortPathSequence(),
            "only the covered write leaves the short path");
    }

    @Test
    void theReadoutPublishesTheClaimFaceAndItsZeroValues() {
        List<String> export = KernelReadings.export(KernelModule.instance());

        for (String key : List.of("write.controlled.enabled=", "write.controlled.slots=",
            "write.controlled.refused=", "write.controlled.refused.wildcard=",
            "write.controlled.attempts=", "write.controlled.default_path=",
            "write.controlled.captured=", "write.controlled.rejected=",
            "write.controlled.not_executable=", "write.controlled.conservation_ok=",
            "write.controlled.landed=", "write.controlled.last_refusal.reason=",
            "write.controlled.last_refusal.expected_version=",
            "write.controlled.last_refusal.carried_version=")) {
            assertTrue(export.stream().anyMatch(line -> line.startsWith(key)), "missing " + key);
        }
        assertTrue(export.stream().anyMatch(line -> line.startsWith("commit.entries=")),
            "the landing face keeps its own field");
    }

    private static void assertRefused(Scratch scratch, String reason, long version) {
        assertEquals(1, scratch.guard.classifyBlockWrite(LEVEL),
            "a covered write whose contract fails does not take the short path");

        assertEquals(Verdict.REJECTED, scratch.slots.lastDecision().verdict());
        assertEquals(reason, scratch.slots.lastDecision().reason());
        WriteRefusal refusal = scratch.slots.lastRefusal();
        assertEquals(reason, refusal.reason());
        assertEquals(WORLD, refusal.worldId());
        assertEquals(DOMAIN, refusal.domainId());
        assertEquals(WriteLevel.REGION, refusal.level());
        assertEquals(SEGMENT, refusal.segment());
        assertEquals(PLAN_SEQUENCE, refusal.planSequence());
        assertEquals(NODE_KEY, refusal.planNodeKey());
        assertEquals(TICK, refusal.tickIndex());
        assertEquals(version, refusal.expectedVersion());
        assertEquals(GENERATION, refusal.slotGeneration());
        assertFalse(refusal.writeSetDigest().isEmpty());
        assertEquals(1L, scratch.slots.rejectedCount());
        assertEquals(0L, scratch.slots.capturedCount());
        assertEquals(0L, scratch.slots.defaultPathCount());
        assertTrue(scratch.slots.conservationHolds());
        assertEquals(0L, scratch.counters.attempts(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN,
            HolderKind.REGISTERED), "a refused claim is not counted on the short path");
    }

    /** The short path of the revision before this change, driven over the same states. */
    private static List<Integer> parentSequence(Scratch scratch) {
        List<Integer> sequence = new ArrayList<>();
        scratch.guard.refresh(false, false, false, TICK);
        sequence.add(scratch.guard.classifyBlockWrite(LEVEL));
        scratch.guard.refresh(true, false, false, TICK);
        sequence.add(scratch.guard.classifyBlockWrite(LEVEL));
        sequence.add(scratch.otherThreadClassification());
        scratch.guard.refresh(false, false, false, TICK);
        sequence.add(scratch.guard.classifyBlockWrite(LEVEL));
        scratch.guard.refresh(true, false, false, TICK);
        sequence.add(scratch.otherThreadClassification());
        return sequence;
    }

    /** The write paths, the registries and the claim table of one process, wired the way the kernel
     * wires them; only the slot table's contents and the claim source are fixtures. */
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
        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final FixtureSource source = new FixtureSource();
        private final WriteControlledSlots slots =
            new WriteControlledSlots(owners, versions, guard.worldEpochs(), source);

        private Scratch() {
            segment.bindOwnerThread(Thread.currentThread());
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), SITE);
            guard.versionSlots(versions);
            guard.controlledSlots(slots);
            guard.refresh(true, false, false, TICK);
            guard.noteLiveWorlds(List.of(WORLD));
            versions.refresh(true);
            slots.refresh(false);
        }

        private long version() {
            return versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
        }

        private void seat(Slot slot) {
            seat(slot, versions.grant(WORLD, WriteLevel.REGION, DOMAIN), GENERATION);
        }

        private void seat(Slot slot, long version, long epoch) {
            owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, epoch, version, 0L, 0L,
                HolderKind.REGISTERED, SITE));
            slots.refresh(true);
            assertEquals(Refusal.NONE, slots.register(slot));
        }

        private Slot seatSyntheticSlot() {
            long version = version();
            Slot slot = syntheticSlot(version, TICK + 40L, NODE_KEY);
            seat(slot, version, GENERATION);
            return slot;
        }

        private Slot syntheticSlotForHolder(String site, long expectedVersion) {
            return new Slot(new SlotKey(WORLD, DOMAIN, WriteLevel.REGION, SEGMENT, PLAN_SEQUENCE),
                LEVEL, NODE_KEY, POSITION, expectedVersion, GENERATION,
                guard.worldEpochs().epochOf(WORLD), TICK + 40L, HolderKind.REGISTERED, site,
                Set.of(DOMAIN));
        }

        private Slot syntheticSlot(long expectedVersion, long expireTick, String nodeKey) {
            return syntheticSlotWith(WORLD, DOMAIN, SEGMENT, PLAN_SEQUENCE, expectedVersion,
                Set.of(DOMAIN), expireTick, nodeKey);
        }

        private Slot syntheticSlotWith(String world, String domain, String segment, long planSequence,
                                       long expectedVersion, Set<String> writeSet) {
            return syntheticSlotWith(world, domain, segment, planSequence, expectedVersion, writeSet,
                TICK + 40L, NODE_KEY);
        }

        private Slot syntheticSlotWith(String world, String domain, String segment, long planSequence,
                                       long expectedVersion, Set<String> writeSet, long expireTick,
                                       String nodeKey) {
            return new Slot(new SlotKey(world, domain, WriteLevel.REGION, segment, planSequence), LEVEL,
                nodeKey, POSITION, expectedVersion, GENERATION, guard.worldEpochs().epochOf(WORLD),
                expireTick, HolderKind.REGISTERED, SITE, writeSet);
        }

        private WriteVersionSlots versionSlots() {
            return versions;
        }

        private List<Integer> shortPathSequence() {
            return parentSequence(this);
        }

        private int otherThreadClassification() {
            int[] answer = new int[1];
            Thread thread = new Thread(() -> answer[0] = guard.classifyBlockWrite(LEVEL),
                "another-thread");
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return answer[0];
        }

        /** The claim source a deployment would wire: which world a level is, and which plan step the
         * frozen plan holds. Both answers can be moved to drive the contract terms. */
        private static final class FixtureSource implements WriteControlledSlots.Source {

            private String world = WORLD;
            private boolean planHolds = true;
            private Step step = new Step(PLAN_SEQUENCE, POSITION);

            @Override
            public String worldOf(Object levelRef) {
                return levelRef == LEVEL ? world : "";
            }

            @Override
            public Step stepOf(String worldId, String domainId, String planNodeKey) {
                return planHolds && planNodeKey.equals(NODE_KEY) ? step : null;
            }
        }
    }
}
