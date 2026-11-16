/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.random;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import java.util.Objects;

/**
 * One keyed shared stream: the upstream Xoroshiro128++ generator started from the state of a
 * {@link StreamKey}, with the upstream {@link RandomSource} surface reproduced value for value.
 * The stream counts the generator values it consumed, so a {@link StreamState} can be taken at any
 * point, and it keeps its gaussian cache and fork count.
 *
 * <p>A keyed stream is never reseeded: {@link #setSeed} is refused, and a new stream is addressed
 * by a new key instead. All other draws follow the upstream source exactly, so switching a
 * consumer to a keyed stream does not change any probability formula, bound or distribution.
 */
public final class SharedStream implements RandomSource {

    private static final float FLOAT_UNIT = 5.9604645E-8F;
    private static final double DOUBLE_UNIT = 1.110223E-16F;

    private final StreamKey key;
    private final RandomSupport.Seed128bit seed;
    private final Xoroshiro128PlusPlus generator;
    private long draws;
    private boolean cachedGaussian;
    private double nextGaussian;
    private long forks;

    /**
     * Creates a stream from its key: the initial state is derived from the key, never from the
     * clock, a thread or an object hash.
     *
     * @param key the key naming this stream
     */
    public SharedStream(StreamKey key) {
        this(Objects.requireNonNull(key, "key"), key.deriveSeed());
    }

    private SharedStream(StreamKey key, RandomSupport.Seed128bit seed) {
        this.key = key;
        this.seed = seed;
        this.generator = new Xoroshiro128PlusPlus(seed);
    }

    /** The key this stream is addressed by. */
    public StreamKey key() {
        return key;
    }

    /** The initial 128-bit generator state derived from the key. */
    public RandomSupport.Seed128bit seed() {
        return seed;
    }

    /** The number of generator values consumed since creation or the last restore. */
    public long draws() {
        return draws;
    }

    /** The number of forks this stream has taken; each fork consumes two values. */
    public long forks() {
        return forks;
    }

    /**
     * Takes the versioned state of this stream: the initial state, the consumed value count, the
     * gaussian cache and the fork count.
     *
     * @return the state that restores this stream exactly
     */
    public StreamState snapshot() {
        return new StreamState(StreamState.CURRENT_DATA_VERSION, key, seed.seedLo(), seed.seedHi(), draws,
            cachedGaussian, nextGaussian, forks);
    }

    /**
     * Restores a stream from a snapshot. The generator is rebuilt from the recorded initial state
     * and advances the recorded number of values, so the running state is reproduced without
     * keeping a second generator implementation; a snapshot taken from a released encoding cannot
     * be restored because its version is not the current one.
     *
     * @param state the state to restore
     * @return a stream positioned exactly where the snapshotted stream was
     */
    public static SharedStream restore(StreamState state) {
        Objects.requireNonNull(state, "state");
        SharedStream stream = new SharedStream(state.key(),
            new RandomSupport.Seed128bit(state.seedLo(), state.seedHi()));
        for (long i = 0L; i < state.draws(); i++) {
            stream.generator.nextLong();
        }
        stream.draws = state.draws();
        stream.cachedGaussian = state.hasGaussian();
        stream.nextGaussian = state.gaussian();
        stream.forks = state.forks();
        return stream;
    }

    @Override
    public int nextInt() {
        return (int) nextLong();
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        long value = Integer.toUnsignedLong(nextInt());
        long product = value * (long) bound;
        long low = product & 0xFFFFFFFFL;
        if (low < (long) bound) {
            for (int threshold = Integer.remainderUnsigned(~bound + 1, bound);
                 low < (long) threshold; low = product & 0xFFFFFFFFL) {
                value = Integer.toUnsignedLong(nextInt());
                product = value * (long) bound;
            }
        }
        return (int) (product >> 32);
    }

    @Override
    public long nextLong() {
        draws++;
        return generator.nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return (nextLong() & 1L) != 0L;
    }

    @Override
    public float nextFloat() {
        return (float) (nextLong() >>> 40) * FLOAT_UNIT;
    }

    @Override
    public double nextDouble() {
        return (double) (nextLong() >>> 11) * DOUBLE_UNIT;
    }

    @Override
    public double nextGaussian() {
        if (cachedGaussian) {
            cachedGaussian = false;
            return nextGaussian;
        }
        double first;
        double second;
        double squared;
        do {
            first = 2.0 * nextDouble() - 1.0;
            second = 2.0 * nextDouble() - 1.0;
            squared = first * first + second * second;
        } while (squared >= 1.0 || squared == 0.0);
        double factor = Math.sqrt(-2.0 * Math.log(squared) / squared);
        nextGaussian = second * factor;
        cachedGaussian = true;
        return first * factor;
    }

    @Override
    public void consumeCount(int count) {
        for (int i = 0; i < count; i++) {
            nextLong();
        }
    }

    @Override
    public RandomSource fork() {
        forks++;
        return new XoroshiroRandomSource(nextLong(), nextLong());
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        forks++;
        return new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(nextLong(), nextLong());
    }

    @Override
    public void setSeed(long seed) {
        throw new UnsupportedOperationException(
            "a keyed shared stream is not reseeded; derive a new stream key instead");
    }
}
