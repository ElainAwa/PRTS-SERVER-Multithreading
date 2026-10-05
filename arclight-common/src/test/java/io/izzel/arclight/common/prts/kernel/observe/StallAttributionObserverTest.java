/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsBlockEntityCosts;
import io.izzel.arclight.common.prts.support.PrtsEntityCosts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the attribution face makes of two instances of different types and of a busy boundary. */
class StallAttributionObserverTest {

    private static final long MILLIS = 1_000_000L;

    public static final class Position {

        private final int x;
        private final int y;
        private final int z;

        Position(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }
    }

    public static final class Ticker {

        private final String type;
        private final Position position;

        Ticker(String type, int x, int y, int z) {
            this.type = type;
            this.position = new Position(x, y, z);
        }

        public String getType() {
            return type;
        }

        public Position getPos() {
            return position;
        }
    }

    public static final class Citizen {

        private final Position position = new Position(4, 5, 6);

        public Position blockPosition() {
            return position;
        }
    }

    private StallAttributionObserver observer;

    @AfterEach
    void detach() {
        if (observer != null) {
            observer.detach();
        }
        PrtsBlockEntityCosts.install(null);
        PrtsEntityCosts.install(null);
    }

    private static LoadThreadObserver.Boundary boundary(long tick, long wall, boolean busy) {
        return new LoadThreadObserver.Boundary(tick, wall, wall, 0L, 0L, wall, false, busy, 0L, 0L);
    }

    @Test
    void twoInstancesOfOneTypeAreCountedSeparatelyAndTheCostliestIsNamed() {
        observer = new StallAttributionObserver();
        observer.attach();
        assertSame(observer, PrtsBlockEntityCosts.watcher());
        assertSame(observer, PrtsEntityCosts.watcher());
        assertTrue(observer.installed());

        Ticker first = new Ticker("create:crushing_wheel", 10, 64, 20);
        Ticker second = new Ticker("create:crushing_wheel", 11, 64, 20);
        Ticker other = new Ticker("minecraft:furnace", 12, 64, 20);
        PrtsBlockEntityCosts.blockEntityTick(first, null, "minecraft:overworld", 5L * MILLIS, -1L);
        PrtsBlockEntityCosts.blockEntityTick(second, null, "minecraft:overworld", 3L * MILLIS, -1L);
        PrtsBlockEntityCosts.blockEntityTick(other, null, "minecraft:overworld", 1L * MILLIS, -1L);
        PrtsBlockEntityCosts.blockEntityTick(second, null, "minecraft:overworld", 9L * MILLIS, -1L);

        assertEquals(18L * MILLIS, observer.totalWall(true));
        assertEquals(4L, observer.totalTicks(true));
        assertEquals(3L, observer.totalInstances(true));
        List<StallAttributionObserver.Row> types = observer.types(true, 3);
        assertEquals("create:crushing_wheel", types.get(0).key());
        assertEquals(17L * MILLIS, types.get(0).wallNanos());
        assertEquals(2L, types.get(0).instances());
        assertEquals(12L * MILLIS, types.get(0).maxInstanceNanos());
        assertEquals("create:crushing_wheel@11,64,20", types.get(0).worst());
        assertEquals("minecraft:furnace", types.get(1).key());
        assertEquals(1, types.get(1).instances());
        assertEquals(2, observer.typeCount(true));

        PrtsBlockEntityCosts.segmentTick("minecraft:overworld", 20L * MILLIS);
        assertEquals(20L * MILLIS, observer.segmentWall(true));
        Citizen citizen = new Citizen();
        PrtsEntityCosts.entityTick(citizen, null, "minecraft:overworld", 4L * MILLIS, -1L);
        assertEquals(4L * MILLIS, observer.totalWall(false));
        assertEquals("Citizen", observer.types(false, 1).get(0).key());
        assertEquals("Citizen@4,5,6", observer.types(false, 1).get(0).worst());

        observer.detach();
        assertFalse(observer.installed());
        assertFalse(PrtsBlockEntityCosts.installed());
        observer.reset();
        assertEquals(0L, observer.totalWall(true));
    }

    @Test
    void aBusyBoundaryOpensAnEpisodeThatTheNextQuietBoundaryCloses() {
        observer = new StallAttributionObserver();
        observer.attach();
        Ticker ticker = new Ticker("create:mechanical_press", 1, 2, 3);
        PrtsBlockEntityCosts.blockEntityTick(ticker, null, "minecraft:overworld", 40L * MILLIS, 30L * MILLIS);

        observer.tickBoundary(boundary(7L, 198_000L * MILLIS, true));
        assertEquals(1L, observer.episodeCount());
        assertEquals(0, observer.episodes().size(), "the episode is still open");
        PrtsBlockEntityCosts.blockEntityTick(ticker, null, "minecraft:overworld", 20L * MILLIS, 10L * MILLIS);

        observer.tickBoundary(boundary(8L, 60L * MILLIS, false));
        List<StallAttributionObserver.Episode> episodes = observer.episodes();
        assertEquals(1, episodes.size());
        StallAttributionObserver.Episode episode = episodes.get(0);
        assertEquals(7L, episode.tick());
        assertEquals(198_000L * MILLIS, episode.wallNanos());
        assertEquals(30L * MILLIS, episode.blockEntityCpuNanos());
        assertEquals(40L * MILLIS, episode.blockEntityNanos(),
            "the tick that closed the episode is not part of it");
        assertEquals("create:mechanical_press", episode.topTypes().get(0).key());
        assertEquals(40L * MILLIS, episode.topTypes().get(0).wallNanos());
        assertEquals("create", episode.topMods().get(0).key());
    }

    @Test
    void aTickOverTheLongBoundIsKeptWithItsInstance() {
        observer = new StallAttributionObserver();
        observer.attach();
        Ticker ticker = new Ticker("minecraft:blast_furnace", 7, 8, 9);
        PrtsBlockEntityCosts.blockEntityTick(ticker, null, "minecraft:overworld", 2L * MILLIS, -1L);
        PrtsBlockEntityCosts.blockEntityTick(ticker, null, "minecraft:overworld", 250L * MILLIS, 200L * MILLIS);

        List<StallAttributionObserver.LongTick> kept = observer.longTicks();
        assertEquals(1, kept.size());
        assertEquals("minecraft:blast_furnace", kept.get(0).type());
        assertEquals("minecraft", kept.get(0).mod());
        assertEquals(250L * MILLIS, kept.get(0).wallNanos());
        assertEquals(200L * MILLIS, kept.get(0).cpuNanos());
        assertEquals("minecraft:blast_furnace@7,8,9", kept.get(0).label());
    }
}
