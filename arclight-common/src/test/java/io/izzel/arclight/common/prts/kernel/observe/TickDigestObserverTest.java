/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tick digest: one row per (tick, world, probe), a bounded window, and the two ways a row can
 * fail to belong to a world. */
class TickDigestObserverTest {

    private static final String WORLD = "minecraft:overworld";

    @AfterEach
    void detach() {
        PrtsPipelineRows.installOwnerTap(null);
        PrtsPipelineRows.bindMailboxWorlds(java.util.Map.of());
    }

    @Test
    void oneRowPerWorldAndProbeIsFoldedPerTick() {
        TickDigestObserver digest = new TickDigestObserver(4, List.of(WORLD));
        digest.attach();
        digest.ownerTask(WORLD, "worldgen");
        digest.ownerTask(WORLD, "worldgen");
        digest.ownerRound(WORLD, "light");
        digest.noteTick(1L);
        assertEquals(3, digest.rows().size(), "one row per probe");
        assertEquals(2.0, row(digest, TickDigestObserver.PROBE_CHUNK).rows());
        assertEquals(0.0, row(digest, TickDigestObserver.PROBE_CHUNK).rounds());
        assertEquals(0.0, row(digest, TickDigestObserver.PROBE_LIGHT).rows());
        assertEquals(1.0, row(digest, TickDigestObserver.PROBE_LIGHT).rounds());
        assertEquals(0.0, row(digest, TickDigestObserver.PROBE_WRITE).rows());
        assertEquals(WORLD, row(digest, TickDigestObserver.PROBE_CHUNK).world());
    }

    @Test
    void theWindowKeepsTheNewestTicks() {
        TickDigestObserver digest = new TickDigestObserver(2, List.of(WORLD));
        digest.attach();
        for (long tick = 1L; tick <= 3L; tick++) {
            digest.ownerTask(WORLD, "worldgen");
            digest.noteTick(tick);
        }
        assertEquals(2L, digest.ticks());
        assertEquals(1L, digest.firstTick(), "the first tick folded is not evicted by the window");
        assertEquals(3L, digest.lastTick());
        assertEquals(9L, digest.rowsFolded());
        assertEquals(6, digest.rows().size(), "the window holds the newest two ticks only");
    }

    @Test
    void aMailboxThatIsNotPlacedIsCountedApart() {
        TickDigestObserver digest = new TickDigestObserver(4, List.of(WORLD));
        digest.attach();
        digest.ownerTask(PrtsPipelineRows.UNPLACED_WORLD, "worldgen");
        digest.noteTick(1L);
        assertEquals(1L, digest.unplacedRows());
        assertEquals(0.0, row(digest, TickDigestObserver.PROBE_CHUNK).rows());
    }

    @Test
    void aMailboxOutsideTheProbesIsCountedApart() {
        TickDigestObserver digest = new TickDigestObserver(4, List.of(WORLD));
        digest.attach();
        digest.ownerTask(WORLD, "sorter");
        digest.noteTick(1L);
        assertEquals(1L, digest.otherMailboxRows());
        assertEquals(0.0, row(digest, TickDigestObserver.PROBE_CHUNK).rows());
    }

    @Test
    void aTickWithoutAWorldFoldsNoRowAndSaysSo() {
        TickDigestObserver digest = new TickDigestObserver(4, List.of());
        digest.attach();
        digest.noteTick(1L);
        assertEquals(1L, digest.droppedTicks());
        assertTrue(digest.rows().isEmpty());
    }

    @Test
    void anObserverWithoutAWindowInstallsNothing() {
        TickDigestObserver digest = new TickDigestObserver(0, List.of(WORLD));
        digest.attach();
        assertFalse(digest.armed());
        assertFalse(PrtsPipelineRows.ownerTapInstalled(), "an unarmed window installs no tap");
    }

    @Test
    void theDeclaredFixtureMovesTheRowItNames() {
        TickDigestObserver plain = new TickDigestObserver(4, List.of(WORLD));
        plain.attach();
        plain.ownerTask(WORLD, "worldgen");
        plain.noteTick(1L);
        plain.noteTick(2L);
        TickDigestObserver moved = new TickDigestObserver(4, List.of(WORLD));
        moved.deviation(tick -> tick == 2L);
        moved.attach();
        moved.ownerTask(WORLD, "worldgen");
        moved.noteTick(1L);
        moved.noteTick(2L);
        assertEquals(3L, moved.deviatedRows(), "the fixture answers for every probe of that tick");
        assertEquals(0.0, row(tickRow(plain, 2L), TickDigestObserver.PROBE_CHUNK).rows());
        assertEquals(1.0, row(tickRow(moved, 2L), TickDigestObserver.PROBE_CHUNK).rows());
        assertEquals(row(tickRow(plain, 1L), TickDigestObserver.PROBE_CHUNK).value(),
            row(tickRow(moved, 1L), TickDigestObserver.PROBE_CHUNK).value());
        assertNotEquals(row(tickRow(plain, 2L), TickDigestObserver.PROBE_CHUNK).value(),
            row(tickRow(moved, 2L), TickDigestObserver.PROBE_CHUNK).value());
    }

    private static TickDigestObserver.Row row(TickDigestObserver digest, String probe) {
        return row(digest.rows(), probe);
    }

    private static TickDigestObserver.Row row(List<TickDigestObserver.Row> rows, String probe) {
        for (TickDigestObserver.Row row : rows) {
            if (row.probe().equals(probe)) {
                return row;
            }
        }
        throw new AssertionError("no row for probe " + probe);
    }

    private static List<TickDigestObserver.Row> tickRow(TickDigestObserver digest, long tick) {
        return digest.rows().stream().filter(row -> row.tick() == tick).toList();
    }
}
