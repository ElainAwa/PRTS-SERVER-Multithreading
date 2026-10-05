/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the partition of a chunk set makes of the eight neighbour relation and of the identity. */
class RegionIdentityObserverTest {

    private static long[] chunks(int[][] coordinates) {
        long[] packed = new long[coordinates.length];
        for (int index = 0; index < coordinates.length; index++) {
            packed[index] = RegionIdentityObserver.pack(coordinates[index][0], coordinates[index][1]);
        }
        return packed;
    }

    private static void assertRoundTrip(int x, int z) {
        long packed = RegionIdentityObserver.pack(x, z);
        assertEquals(x, RegionIdentityObserver.unpackX(packed));
        assertEquals(z, RegionIdentityObserver.unpackZ(packed));
    }

    @Test
    void thePackingIsItsOwnInverseOnBothSidesOfZero() {
        assertRoundTrip(0, 0);
        assertRoundTrip(17, -3);
        assertRoundTrip(-1, -1);
        assertRoundTrip(Integer.MIN_VALUE + 1, Integer.MAX_VALUE);
        assertRoundTrip(1234567, -7654321);
    }

    @Test
    void anEmptyWorldHasNoComponent() {
        RegionIdentityObserver.Partition partition = RegionIdentityObserver.partition(new long[0]);
        assertEquals(0, partition.count());
        assertEquals(0, partition.chunks());
    }

    @Test
    void aSolidSquareIsOneComponentAndADiagonalTouchIsEnoughToJoin() {
        RegionIdentityObserver.Partition square = RegionIdentityObserver.partition(
            chunks(new int[][] {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}, {0, 2}, {1, 2}, {2, 2}}));
        assertEquals(1, square.count());
        assertEquals(9, square.chunks());
        assertEquals(9, square.components().get(0).chunks());

        RegionIdentityObserver.Partition diagonal = RegionIdentityObserver.partition(
            chunks(new int[][] {{0, 0}, {1, 1}}));
        assertEquals(1, diagonal.count(), "a corner touch joins two chunks");

        RegionIdentityObserver.Partition apart = RegionIdentityObserver.partition(
            chunks(new int[][] {{0, 0}, {2, 1}}));
        assertEquals(2, apart.count(), "a one chunk gap keeps them apart");
    }

    @Test
    void componentsComeOutInAnchorOrderAndTheOrderOfTheInputDoesNotMatter() {
        long[] forward = chunks(new int[][] {{-4, 7}, {-3, 7}, {30, 30}, {31, 30}, {32, 30}});
        long[] backward = chunks(new int[][] {{32, 30}, {31, 30}, {30, 30}, {-3, 7}, {-4, 7}});
        RegionIdentityObserver.Partition left = RegionIdentityObserver.partition(forward);
        RegionIdentityObserver.Partition right = RegionIdentityObserver.partition(backward);
        assertEquals(2, left.count());
        assertEquals(RegionIdentityObserver.pack(-4, 7), left.components().get(0).anchor());
        assertEquals(2, left.components().get(0).chunks());
        assertEquals(RegionIdentityObserver.pack(30, 30), left.components().get(1).anchor());
        assertEquals(3, left.components().get(1).chunks());
        assertEquals(left.hash(), right.hash());
    }

    @Test
    void aRepeatedChunkIsCountedOnce() {
        RegionIdentityObserver.Partition partition = RegionIdentityObserver.partition(
            chunks(new int[][] {{5, 5}, {5, 5}, {5, 6}, {5, 6}}));
        assertEquals(1, partition.count());
        assertEquals(2, partition.chunks());
    }

    @Test
    void theIdentityOfAWorldFollowsItsChunkSetExactly() {
        String one = RegionIdentityObserver.identityHash(List.of(
            new RegionIdentityObserver.WorldChunks("minecraft:overworld",
                chunks(new int[][] {{0, 0}, {1, 0}}))));
        String same = RegionIdentityObserver.identityHash(List.of(
            new RegionIdentityObserver.WorldChunks("minecraft:overworld",
                chunks(new int[][] {{1, 0}, {0, 0}}))));
        String other = RegionIdentityObserver.identityHash(List.of(
            new RegionIdentityObserver.WorldChunks("minecraft:overworld",
                chunks(new int[][] {{0, 0}, {1, 0}, {2, 0}}))));
        String otherWorld = RegionIdentityObserver.identityHash(List.of(
            new RegionIdentityObserver.WorldChunks("minecraft:the_nether",
                chunks(new int[][] {{0, 0}, {1, 0}}))));
        assertEquals(one, same);
        assertNotEquals(one, other, "one more chunk is a different partition");
        assertNotEquals(one, otherWorld, "the world id is part of the identity");
        assertTrue(one.length() > 8);
    }

    @Test
    void twoWorldsAreHashedInWorldIdOrderAndThePairIsNotTheSumOfItsParts() {
        RegionIdentityObserver.WorldChunks overworld = new RegionIdentityObserver.WorldChunks(
            "minecraft:overworld", chunks(new int[][] {{0, 0}}));
        RegionIdentityObserver.WorldChunks nether = new RegionIdentityObserver.WorldChunks(
            "minecraft:the_nether", chunks(new int[][] {{0, 0}}));
        assertEquals(RegionIdentityObserver.identityHash(List.of(overworld, nether)),
            RegionIdentityObserver.identityHash(List.of(nether, overworld)));
        assertNotEquals(RegionIdentityObserver.identityHash(List.of(overworld, nether)),
            RegionIdentityObserver.identityHash(List.of(overworld)));
    }
}
