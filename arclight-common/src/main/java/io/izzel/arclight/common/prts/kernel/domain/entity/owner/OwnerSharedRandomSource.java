/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.random.SharedStream;
import io.izzel.arclight.common.prts.kernel.random.SharedStreamTable;
import io.izzel.arclight.common.prts.kernel.random.StreamKey;
import io.izzel.arclight.common.prts.kernel.random.StreamState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

import java.util.List;

/** The level's one random field routed to keyed shared streams: a draw of an owner body goes to the
 * stream its business/object identity names, while a draw outside an owner body keeps the upstream
 * host source. A stream is created once per key and persists in this table; it is never re-derived or
 * reseeded, and its snapshot is the stream position the world can save. */
public final class OwnerSharedRandomSource implements RandomSource {

    private final RandomSource base;
    private final SharedStreamTable table = new SharedStreamTable();

    public OwnerSharedRandomSource(RandomSource base) {
        this.base = base;
    }

    private RandomSource routed() {
        OwnerRandomContext.Row row = OwnerRandomContext.current();
        if (row == null) {
            // A draw outside an owner body inside a read node would move the host's shared stream
            // without the row program that must order it: the fence refuses it before the draw.
            io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeReadGuard.fence(
                io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeReadGuard.Refusal
                    .DRAW);
            return base;
        }
        SharedStream stream = table.stream(StreamKey.of(row.worldSeed(), row.worldIdentity(),
            row.dimension(), row.business(), row.objectIdentity()));
        return stream;
    }

    /** The persistent table of streams this level random has addressed, in first-use order. */
    public SharedStreamTable table() {
        return table;
    }

    /** The versioned stream positions a world save can store. */
    public List<StreamState> snapshot() {
        return table.snapshot();
    }

    /** Restores the stream positions of this level random; the next draws continue instead of
     * reseeding. */
    public void restore(List<StreamState> states) {
        table.restoreState(states);
    }

    @Override
    public RandomSource fork() {
        return routed().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return routed().forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        if (OwnerRandomContext.current() != null) {
            // A keyed stream is addressed by its key, never reseeded by a caller inside an owner body.
            routed().setSeed(seed);
            return;
        }
        base.setSeed(seed);
    }

    @Override
    public int nextInt() {
        return routed().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return routed().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return routed().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return routed().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return routed().nextFloat();
    }

    @Override
    public double nextDouble() {
        return routed().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return routed().nextGaussian();
    }

    @Override
    public void consumeCount(int count) {
        routed().consumeCount(count);
    }
}
