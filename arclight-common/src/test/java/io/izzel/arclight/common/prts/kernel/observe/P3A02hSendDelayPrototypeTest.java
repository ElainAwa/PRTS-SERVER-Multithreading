/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.shares.WorldResChildBudget;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-only prototype of a bounded send delay: it holds the real PlayerChunkSender pending set
 * instead of touching the real send path and uses a real B01-A over-parent budget as pressure. The
 * gate model is the same decision shape the production action uses, so the prototype proves the
 * hold/release/timeout/loss behaviour on the real pending container before product wiring. */
class P3A02hSendDelayPrototypeTest {

    private static final String WORLD = "minecraft:overworld";
    private static final long EPOCH = 7L;
    private static final int CAPACITY = 8;

    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final PlayerChunkSender sender = new PlayerChunkSender(false);
    private final LongSet pending;
    private final List<Long> sent = new ArrayList<>();

    P3A02hSendDelayPrototypeTest() throws ReflectiveOperationException {
        Field field = PlayerChunkSender.class.getDeclaredField("pendingChunks");
        field.setAccessible(true);
        this.pending = (LongSet) field.get(this.sender);
    }

    private void mark(long... keys) {
        for (long key : keys) {
            pending.add(key);
        }
    }

    private void drain() {
        for (long key : pending.toLongArray()) {
            pending.remove(key);
            sent.add(key);
        }
    }

    private static WorldResChildBudget budget(boolean over) {
        ShareTable table = new SharePlanner().plan(List.of(WORLD), 20L, Map.of());
        double parent = table.row(WORLD, ShareClass.WORLDRES).shareMs();
        return WorldResChildBudget.settle(table, WORLD, EPOCH,
            WorldResChildBudget.ChildDemand.ofTaskNanos(1_000_000L, 500_000L),
            over ? parent * 2.0 : 0.0, 0.0);
    }

    private static final class DelayGate {

        private final int maxDelayTicks;
        private final int maxWorlds;
        private final int maxPending;
        private final Map<String, State> states = new HashMap<>();

        private DelayGate(int maxDelayTicks, int maxWorlds, int maxPending) {
            this.maxDelayTicks = maxDelayTicks;
            this.maxWorlds = maxWorlds;
            this.maxPending = maxPending;
        }

        private boolean defer(String world, long epoch, long tick,
                              WorldResChildBudget settlement, int pendingCount) {
            if (settlement == null || settlement.epoch() != epoch
                || settlement.status() != WorldResChildBudget.Status.PARENT_OVER
                || pendingCount <= 0 || pendingCount > maxPending) {
                states.remove(world);
                return false;
            }
            State state = states.get(world);
            if (state == null) {
                if (states.size() >= maxWorlds) {
                    return false;
                }
                state = new State(epoch, tick);
                states.put(world, state);
            }
            if (state.epoch != epoch || tick - state.firstTick > maxDelayTicks) {
                states.remove(world);
                return false;
            }
            state.lastTick = tick;
            return true;
        }

        private void reset() {
            states.clear();
        }

        private int states() {
            return states.size();
        }

        private static final class State {

            private final long epoch;
            private final long firstTick;
            private long lastTick;

            private State(long epoch, long firstTick) {
                this.epoch = epoch;
                this.firstTick = firstTick;
                this.lastTick = firstTick;
            }
        }
    }

    @Test
    void overpressureHoldsTheRealPendingSetAndRecoveryDrainsExactlyOnce() {
        ChunkHolder holder = new ChunkHolder(new ChunkPos(0, 0), 33,
            new LevelHeightAccessor() {
                @Override
                public int getHeight() {
                    return 384;
                }

                @Override
                public int getMinBuildHeight() {
                    return -64;
                }
            }, null,
            (pos, queueLevelGetter, ticketLevel, queueLevelSetter) -> {
            }, (pos, boundaryOnly) -> List.of());
        assertNotNull(holder.getSendSyncFuture());

        mark(1L, 2L, 3L);
        DelayGate gate = new DelayGate(3, 2, CAPACITY);
        assertTrue(gate.defer(WORLD, EPOCH, 1L, budget(true), pending.size()));
        assertTrue(gate.defer(WORLD, EPOCH, 2L, budget(true), pending.size()));
        assertEquals(3, pending.size());
        assertTrue(sent.isEmpty());

        assertFalse(gate.defer(WORLD, EPOCH, 3L, budget(false), pending.size()));
        drain();
        assertEquals(3, sent.size());
        assertEquals(0, pending.size());
        drain();
        assertEquals(3, sent.size(), "a drained chunk is never sent twice");
    }

    @Test
    void timeoutReleasesEvenWhenOverpressurePersistsWithoutLosingChunks() {
        mark(10L, 11L, 12L);
        DelayGate gate = new DelayGate(2, 2, CAPACITY);
        assertTrue(gate.defer(WORLD, EPOCH, 1L, budget(true), pending.size()));
        assertTrue(gate.defer(WORLD, EPOCH, 2L, budget(true), pending.size()));
        assertFalse(gate.defer(WORLD, EPOCH, 4L, budget(true), pending.size()));
        drain();
        assertEquals(3, sent.size());
        assertEquals(0, pending.size());
    }

    @Test
    void staleEpochAndMissingBudgetFallOpenAndClearTheState() {
        mark(20L, 21L);
        DelayGate gate = new DelayGate(3, 2, CAPACITY);
        assertTrue(gate.defer(WORLD, EPOCH, 1L, budget(true), pending.size()));
        WorldResChildBudget otherEpoch = budget(true);
        assertFalse(gate.defer(WORLD, EPOCH + 1, 2L, otherEpoch, pending.size()));
        assertEquals(0, gate.states());
        assertFalse(gate.defer(WORLD, EPOCH, 3L, null, pending.size()));
        drain();
        assertEquals(2, sent.size());
    }

    @Test
    void boundedWorldsAndPendingFallOpenInsteadOfGrowingWithoutBound() {
        DelayGate gate = new DelayGate(3, 1, 4);
        mark(30L, 31L, 32L, 33L, 34L);
        assertFalse(gate.defer(WORLD, EPOCH, 1L, budget(true), pending.size()));
        assertTrue(gate.defer("minecraft:the_end", EPOCH, 1L, budget(true), 3));
        assertFalse(gate.defer("minecraft:nether", EPOCH, 1L, budget(true), 3));
        assertEquals(1, gate.states());
        gate.reset();
        assertEquals(0, gate.states());
    }

    @Test
    void thePrototypeUsesTheRealBudgetStatusAndRealPendingContainer() {
        assertEquals(WorldResChildBudget.Status.PARENT_OVER, budget(true).status());
        assertEquals(WorldResChildBudget.Status.SETTLED, budget(false).status());
        mark(40L);
        assertEquals(1, pending.size());
        assertTrue(pending.contains(40L));
    }
}
