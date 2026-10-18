/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The object enumeration of the placement walk: the target sorter of the chunk map and the second
 * sorter of its distance manager share a mailbox name, so only object identity separates them. */
class TickDigestObserverTicketThrottlerTest {

    private static final String WORLD = "minecraft:overworld";

    private static final Executor DIRECT = Runnable::run;

    @AfterEach
    void clear() {
        PrtsPipelineRows.install(null);
        PrtsPipelineRows.installOwnerTap(null);
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());
        PrtsPipelineRows.bindNonTargetSorters(Set.of());
    }

    @Test
    void theTargetProcessorsAndTheTicketSorterAreTwoObjectSets() {
        SorterFixture targetSorter = new SorterFixture("worldgen");
        SorterFixture ticketSorter = new SorterFixture("sorter");
        DistanceManagerFixture distanceManager = new DistanceManagerFixture(ticketSorter);
        ChunkMapFixture chunkMap = new ChunkMapFixture(targetSorter, distanceManager);

        List<Object> processors = new ArrayList<>(TickDigestObserver.mailboxesOf(chunkMap));
        assertEquals(4, processors.size(), "three queue keys and the target sorter mailbox");
        Object ticketThrottler = TickDigestObserver.ticketThrottlerOf(chunkMap);
        assertNotNull(ticketThrottler);
        assertSame(ticketSorter, ticketThrottler);
        assertTrue(processors.stream().noneMatch(mailbox -> mailbox == ticketSorter.mailbox),
            "the ticket throttler mailbox is not part of the target processor set");
        assertEquals(1, processors.stream().filter(mailbox -> mailbox == targetSorter.mailbox)
            .count(), "the target sorter mailbox is part of it");
    }

    @Test
    void aChunkMapWithoutTheKnownFieldsContributesNothing() {
        Object foreignLevel = new Object();
        assertTrue(TickDigestObserver.mailboxesOf(foreignLevel).isEmpty(),
            "a level whose chunk map is not the vanilla shape is not a target source");
        assertNull(TickDigestObserver.ticketThrottlerOf(foreignLevel));
    }

    @Test
    void theFieldLookupWalksTheSuperclassOfTheRealDistanceManager() {
        SorterFixture ticketSorter = new SorterFixture("sorter");
        DistanceManagerFixture distanceManager = new DistanceManagerFixture(ticketSorter);
        ChunkMapFixture chunkMap = new ChunkMapFixture(new SorterFixture("worldgen"),
            distanceManager);
        assertSame(ticketSorter, TickDigestObserver.ticketThrottlerOf(chunkMap),
            "the ticket throttler field is declared on the parent class");
    }

    @Test
    void attachDetachAndResetLeaveTheClassificationStable() {
        TickDigestObserver digest = new TickDigestObserver(4, List.of(WORLD));
        digest.attach();
        Object ticketSorter = new Object();
        Object ticketMailbox = new Object();
        PrtsPipelineRows.bindNonTargetSorters(Set.of(ticketSorter));
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of(ticketMailbox));
        assertTrue(PrtsPipelineRows.isNonTargetSorter(ticketSorter));
        assertTrue(PrtsPipelineRows.isNonTargetMailbox(ticketMailbox));

        PrtsPipelineRows.ownerRound(ticketMailbox, "sorter");
        assertEquals(1L, digest.otherMailboxRows(),
            "the non-target round stays on the digest other-mailbox face");
        assertEquals(0L, digest.unplacedRows());

        digest.reset();
        assertTrue(PrtsPipelineRows.isNonTargetSorter(ticketSorter),
            "a counter reset keeps the classification");
        assertTrue(PrtsPipelineRows.isNonTargetMailbox(ticketMailbox));

        digest.detach();
        assertFalse(PrtsPipelineRows.isNonTargetSorter(ticketSorter),
            "a detach leaves no stale classification behind");
        assertFalse(PrtsPipelineRows.isNonTargetMailbox(ticketMailbox));
    }

    static final class SorterFixture {

        final Map<Object, Object> queues = new LinkedHashMap<>();

        final ProcessorMailbox<Runnable> mailbox;

        SorterFixture(String name) {
            for (int index = 0; index < 3; index++) {
                ProcessorHandle<?> handle = ProcessorHandle.of(name + "-" + index,
                    (Unit unit) -> {
                    });
                queues.put(handle, name);
            }
            mailbox = ProcessorMailbox.create(DIRECT, name);
        }
    }

    static final class ChunkMapFixture {

        @SuppressWarnings("unused")
        private final Object queueSorter;

        @SuppressWarnings("unused")
        private final Object distanceManager;

        ChunkMapFixture(Object queueSorter, Object distanceManager) {
            this.queueSorter = queueSorter;
            this.distanceManager = distanceManager;
        }
    }

    static class TicketSorterHolder {

        @SuppressWarnings("unused")
        final Object ticketThrottler;

        TicketSorterHolder(Object ticketThrottler) {
            this.ticketThrottler = ticketThrottler;
        }
    }

    static final class DistanceManagerFixture extends TicketSorterHolder {

        DistanceManagerFixture(Object ticketThrottler) {
            super(ticketThrottler);
        }
    }
}
