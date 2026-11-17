/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk.stage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The build/register split of one chunk holder generation: the pure build may run on a worker and
 * touches no live registry, the serial registration runs once, a repeated registration of the same
 * holder generation answers DUPLICATE without registering or publishing again, and a result of an
 * older holder generation is refused as stale. */
public final class ChunkOwnerMaterializer {

    /** The identity one materialization belongs to. */
    public record Key(long worldIncarnation, long holderGen, long chunkPos) {

        public Key {
            if (worldIncarnation < 0L || holderGen < 0L) {
                throw new IllegalArgumentException("a materialization cannot name a negative lineage");
            }
        }
    }

    /** A built object waiting for its serial registration. */
    public record Built(Key key, Object value) {

        public Built {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** What one registration did. */
    public enum Result {
        REGISTERED,
        DUPLICATE,
        STALE_GENERATION
    }

    /** One registration answer; a duplicate carries no second registration. */
    public record Outcome(Result result, Object value) {
    }

    private final Map<Key, Object> built = new LinkedHashMap<>();
    private final Map<Long, Long> generations = new LinkedHashMap<>();
    private final java.util.Set<Key> registered = new java.util.LinkedHashSet<>();
    private long registeredCount;
    private long duplicateCount;
    private long staleCount;

    /** Runs the pure build of one holder generation; a stale generation builds nothing. */
    public synchronized Built build(Key key, Supplier<Object> pureBuild) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(pureBuild, "pureBuild");
        Long current = generations.get(key.chunkPos());
        if (current != null && key.holderGen() < current) {
            staleCount++;
            return null;
        }
        generations.merge(key.chunkPos(), key.holderGen(), Math::max);
        Built built = new Built(key, pureBuild.get());
        this.built.put(key, built.value());
        return built;
    }

    /** Registers one built object on the serial thread, exactly once per holder generation. */
    public synchronized Outcome register(Built candidate, Consumer<Object> serialRegister) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(serialRegister, "serialRegister");
        Key key = candidate.key();
        Long current = generations.get(key.chunkPos());
        if (current != null && key.holderGen() < current) {
            staleCount++;
            return new Outcome(Result.STALE_GENERATION, null);
        }
        if (registered.contains(key)) {
            duplicateCount++;
            return new Outcome(Result.DUPLICATE, built.get(key));
        }
        serialRegister.accept(candidate.value());
        registered.add(key);
        generations.merge(key.chunkPos(), key.holderGen(), Math::max);
        registeredCount++;
        return new Outcome(Result.REGISTERED, candidate.value());
    }

    /** Advances one chunk's holder generation; an older generation can no longer register. */
    public synchronized void advanceGeneration(Key key) {
        Objects.requireNonNull(key, "key");
        generations.merge(key.chunkPos(), key.holderGen(), Math::max);
    }

    public synchronized long registeredCount() {
        return registeredCount;
    }

    public synchronized long duplicateCount() {
        return duplicateCount;
    }

    public synchronized long staleCount() {
        return staleCount;
    }

    /** Drops every state; test and evidence code only. */
    public synchronized void reset() {
        built.clear();
        generations.clear();
        registered.clear();
        registeredCount = 0;
        duplicateCount = 0;
        staleCount = 0;
    }
}
