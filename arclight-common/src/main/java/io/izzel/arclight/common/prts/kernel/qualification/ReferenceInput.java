/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.random.SharedStream;
import io.izzel.arclight.common.prts.kernel.random.StreamKey;
import io.izzel.arclight.common.prts.kernel.random.StreamState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The fixed input of one reference run: the world and registration version, the plan tick, the
 * arrival rows in their order, the random stream start and position, and the before state. Capture
 * and restore are bit-exact; a restore that does not match the captured input is refused with the
 * offending field named instead of quietly starting from a new state. */
public record ReferenceInput(long worldSeed, String worldId, long registrationVersion,
                             long planTick, int owners, List<Arrival> arrivals,
                             StreamState streamState, long streamPosition,
                             Map<Long, Long> initialState) {

    /** One fixed arrival row. */
    public record Arrival(long key, int entityId, double x, double y, double z, long order,
                          long deadlineNanos) {

        public Arrival {
            if (key < 0L || order < 0L || deadlineNanos < 0L) {
                throw new IllegalArgumentException("an arrival cannot name a negative line");
            }
        }
    }

    /** The result of a capture/restore attempt; a failure names the field and refuses the run. */
    public record RestoreResult(ReferenceInput restored, String failure) {

        public boolean ok() {
            return failure == null || failure.isEmpty();
        }
    }

    public ReferenceInput {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(arrivals, "arrivals");
        Objects.requireNonNull(streamState, "streamState");
        Objects.requireNonNull(initialState, "initialState");
        if (worldId.isEmpty()) {
            throw new IllegalArgumentException("a reference input names its world");
        }
        List<Arrival> ordered = new ArrayList<>(arrivals);
        ordered.sort(java.util.Comparator.comparingLong(Arrival::order));
        arrivals = List.copyOf(ordered);
        initialState = Map.copyOf(initialState);
    }

    /** Captures the fixed input of one reference run. */
    public static ReferenceInput capture(long worldSeed, String worldId, long registrationVersion,
                                         long planTick, int owners, List<Arrival> arrivals,
                                         SharedStream stream, Map<Long, Long> initialState) {
        Objects.requireNonNull(stream, "stream");
        return new ReferenceInput(worldSeed, worldId, registrationVersion, planTick, owners,
            arrivals, stream.snapshot(), stream.draws(), initialState);
    }

    /** Restores to the captured state only when every field still matches; a mismatch is named. */
    public RestoreResult restore(ReferenceInput snapshot) {
        if (snapshot == null) {
            return new RestoreResult(null, "restore-failed:no-snapshot");
        }
        if (worldSeed != snapshot.worldSeed()) {
            return new RestoreResult(null, "restore-failed:worldSeed");
        }
        if (!worldId.equals(snapshot.worldId())) {
            return new RestoreResult(null, "restore-failed:worldId");
        }
        if (registrationVersion != snapshot.registrationVersion()) {
            return new RestoreResult(null, "restore-failed:registrationVersion");
        }
        if (planTick != snapshot.planTick()) {
            return new RestoreResult(null, "restore-failed:planTick");
        }
        if (owners != snapshot.owners()) {
            return new RestoreResult(null, "restore-failed:owners");
        }
        if (!arrivals.equals(snapshot.arrivals())) {
            return new RestoreResult(null, "restore-failed:arrivals");
        }
        if (!streamState.equals(snapshot.streamState())) {
            return new RestoreResult(null, "restore-failed:streamState");
        }
        if (streamPosition != snapshot.streamPosition()) {
            return new RestoreResult(null, "restore-failed:streamPosition");
        }
        if (!initialState.equals(snapshot.initialState())) {
            return new RestoreResult(null, "restore-failed:initialState");
        }
        return new RestoreResult(snapshot, "");
    }

    /** The captured random stream, restored from the fixed state. */
    public SharedStream stream() {
        return SharedStream.restore(streamState);
    }

    /** The keys of the arrival rows, in their fixed order. */
    public List<Long> keys() {
        List<Long> keys = new ArrayList<>(arrivals.size());
        for (Arrival arrival : arrivals) {
            keys.add(arrival.key());
        }
        return List.copyOf(keys);
    }

    /** A copy with one arrival removed, used only to prove a mismatch is detected. */
    public ReferenceInput withoutArrival(int index) {
        List<Arrival> changed = new ArrayList<>(arrivals);
        changed.remove(index);
        return new ReferenceInput(worldSeed, worldId, registrationVersion, planTick, owners,
            changed, streamState, streamPosition, initialState);
    }

    /** The stream position the capture recorded. */
    public long draws() {
        return streamState.draws();
    }

    /** The key the capture used for its deterministic stream. */
    public StreamKey streamKey() {
        return streamState.key();
    }
}
