/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arrival face: one row per (tick, world), the difference the demand counters moved by, the
 * writes the world was handed, and the two ways the face stays off. */
class ArrivalDigestObserverTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER = "minecraft:the_nether";

    private final ChunkDemandObserver demand = new ChunkDemandObserver();

    @AfterEach
    void detach() {
        demand.detach();
    }

    private ArrivalDigestObserver armed() {
        ArrivalDigestObserver arrival = new ArrivalDigestObserver(4);
        arrival.demand(demand);
        demand.attach();
        arrival.attach();
        return arrival;
    }

    @Test
    void oneRowPerWorldCarriesTheDifferenceOfTheTick() {
        ArrivalDigestObserver arrival = armed();
        demand.demandStarted(WORLD, "full", false);
        demand.demandStarted(WORLD, "full", false);
        demand.demandFinished(WORLD, "full", false, true);
        arrival.arrivalTick(1L, List.of(WORLD, OTHER));
        assertEquals(2, arrival.rows().size(), "one row per world");
        assertEquals(2L, row(arrival, WORLD).requests());
        assertEquals(1L, row(arrival, WORLD).satisfied());
        assertEquals(0L, row(arrival, OTHER).requests());
        demand.demandStarted(WORLD, "full", false);
        arrival.arrivalTick(2L, List.of(WORLD, OTHER));
        assertEquals(1L, row(arrival, WORLD, 2L).requests(), "the second row carries the difference");
        assertEquals(0L, row(arrival, WORLD, 2L).satisfied(), "the ask of that tick is unanswered");
        assertEquals(0L, row(arrival, WORLD, 2L).missed());
    }

    @Test
    void aTickWithoutADemandIsAZeroRowAndNotAMissingOne() {
        ArrivalDigestObserver arrival = armed();
        arrival.arrivalTick(1L, List.of(WORLD));
        arrival.arrivalTick(2L, List.of(WORLD));
        assertEquals(2, arrival.rows().size());
        assertEquals(0L, row(arrival, WORLD, 1L).requests());
        assertEquals(0L, row(arrival, WORLD, 2L).requests());
        ArrivalDigestObserver other = armed();
        other.arrivalTick(1L, List.of(WORLD));
        assertEquals(row(arrival, WORLD, 1L).value(), row(other, WORLD, 1L).value(),
            "two runs that moved nothing on one tick fold to the same row");
    }

    @Test
    void theOutstandingFuturesAreALevelAndTheWritesArePerWorld() {
        ArrivalDigestObserver arrival = armed();
        demand.demandStarted(WORLD, "full", false);
        demand.futureTaken(WORLD, "full");
        demand.futureTaken(WORLD, "full");
        arrival.writeAtWorld(WORLD);
        arrival.writeAtWorld(OTHER);
        arrival.arrivalTick(1L, List.of(WORLD, OTHER));
        assertEquals(2L, row(arrival, WORLD).inFlight());
        assertEquals(1L, row(arrival, WORLD).writes());
        assertEquals(1L, row(arrival, OTHER).writes());
        assertEquals(2L, arrival.writesTotal());
        demand.futureCompleted(WORLD, "full", -1L, true);
        arrival.arrivalTick(2L, List.of(WORLD, OTHER));
        assertEquals(1L, row(arrival, WORLD, 2L).inFlight(), "the level moves with the completion");
        assertEquals(0L, row(arrival, WORLD, 2L).writes(), "and the writes are a difference");
    }

    @Test
    void aWorldTheSameTickWasNotFoldedForIsNotInvented() {
        ArrivalDigestObserver arrival = armed();
        arrival.arrivalTick(1L, List.of(WORLD));
        assertEquals(1, arrival.rows().size());
        assertEquals(1L, arrival.worlds().size(), "a world that was not folded is not invented");
    }

    @Test
    void theWindowKeepsTheNewestTicks() {
        ArrivalDigestObserver arrival = new ArrivalDigestObserver(2);
        arrival.demand(demand);
        demand.attach();
        arrival.attach();
        for (long tick = 1L; tick <= 3L; tick++) {
            arrival.arrivalTick(tick, List.of(WORLD));
        }
        assertEquals(2L, arrival.ticks());
        assertEquals(1L, arrival.firstTick());
        assertEquals(3L, arrival.lastTick());
        assertEquals(3L, arrival.rowsFolded());
    }

    @Test
    void aWindowlessObserverFoldsNothing() {
        ArrivalDigestObserver arrival = new ArrivalDigestObserver(0);
        arrival.demand(demand);
        demand.attach();
        arrival.attach();
        assertFalse(arrival.armed());
        arrival.arrivalTick(1L, List.of(WORLD));
        assertTrue(arrival.rows().isEmpty(), "an unarmed face folds no row");
        assertEquals(0L, arrival.rowsFolded());
    }

    @Test
    void aDetachedObserverFoldsNothingEvenWhenArmed() {
        ArrivalDigestObserver arrival = armed();
        arrival.detach();
        arrival.arrivalTick(1L, List.of(WORLD));
        assertTrue(arrival.rows().isEmpty());
        assertTrue(arrival.armed());
    }

    @Test
    void aWriteTheLevelIsNotPlacedOnIsCountedApart() {
        ArrivalDigestObserver arrival = armed();
        arrival.writeAtLevel(new Object());
        assertEquals(1L, arrival.unplacedWrites());
        assertEquals(0L, arrival.writesTotal(), "an unplaced write is not counted into a world");
    }

    @Test
    void aTickThatMovedNothingFoldsAZeroRow() {
        ArrivalDigestObserver arrival = armed();
        arrival.arrivalTick(1L, List.of(WORLD));
        demand.demandStarted(WORLD, "full", false);
        arrival.arrivalTick(2L, List.of(WORLD));
        assertNotEquals(row(arrival, WORLD, 1L).value(), row(arrival, WORLD, 2L).value());
        arrival.arrivalTick(3L, List.of(WORLD));
        assertEquals(0L, row(arrival, WORLD, 3L).requests());
        assertEquals(0L, row(arrival, WORLD, 3L).writes());
        assertEquals(0L, row(arrival, WORLD, 3L).inFlight());
    }

    private static ArrivalDigestObserver.Row row(ArrivalDigestObserver arrival, String world) {
        return row(arrival, world, -1L);
    }

    private static ArrivalDigestObserver.Row row(ArrivalDigestObserver arrival, String world,
                                                 long tick) {
        for (ArrivalDigestObserver.Row row : arrival.rows()) {
            if (row.world().equals(world) && (tick < 0L || row.tick() == tick)) {
                return row;
            }
        }
        throw new AssertionError("no arrival row for " + world + " at " + tick);
    }
}
