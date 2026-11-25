/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.domain.chunk.save.SaveManifestVector;
import io.izzel.arclight.common.prts.kernel.domain.chunk.save.SaveWatermarkLedger;
import io.izzel.arclight.common.prts.kernel.random.SharedStream;
import io.izzel.arclight.common.prts.kernel.random.StreamKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The dual-reference parity harness: over one fixed input it compares the real serial owner
 * execution with the real owner-parallel execution and keeps the unmodified vanilla reference
 * separate. Both owner sides are bindable entries installed by an authorised window harness; while
 * either is unbound the corresponding side refuses by name and no comparison pass is claimed. The
 * arithmetic methods kept below are FIXTURE inputs for the local offline runner only and are never
 * consulted by a comparison pass. */
public final class DualReferenceParityHarness {

    /** The fixed arrival input of one parity run. */
    public record Input(long worldSeed, long registrationVersion, List<Long> keys, int owners) {

        public Input {
            Objects.requireNonNull(keys, "keys");
            if (owners <= 0) {
                throw new IllegalArgumentException("owners must be positive");
            }
        }
    }

    /** One side of the comparison: every witness field plus its canonical digest. */
    public record Result(Map<Long, Long> state, long streamPosition, Map<Long, Long> poiOwner,
                         List<String> events, Map<String, Long> inventory, long publishedRevision,
                         Map<SaveManifestVector.Kind, Long> manifest, String digest) {
    }

    /** The unmodified host reference; this build has no entry for it, so it is NOT_RUN by name. */
    public record VanillaReference(String status, String reason) {
    }

    /** The parity answer with both sides and the two reference halves kept separate. */
    public record Parity(boolean sNativeBitExact, boolean allWitnessesEqual, Result serial,
                         Result parallel, VanillaReference vanilla, List<String> requiredFields) {

        public boolean fullDualReferencePass() {
            return sNativeBitExact && allWitnessesEqual && "PASS".equals(vanilla.status());
        }
    }

    /** A real owner execution entry installed by the window harness for one comparison side. */
    @FunctionalInterface
    public interface Execution {
        Result run(ReferenceInput input);
    }

    private static final VanillaReference VANILLA_NOT_RUN = new VanillaReference("NOT_RUN",
        "no unmodified host serial reference entry exists; the comparison half is not claimed");

    private static final List<String> REQUIRED_FIELDS = List.of("state", "streamPosition", "poiOwner",
        "events", "inventory", "publishedRevision", "manifest", "digest");

    private static volatile Execution serialBinding;
    private static volatile Execution parallelBinding;

    private DualReferenceParityHarness() {
    }

    /** Binds one owner side to a real execution entry; the vanilla side has no owner entry. */
    public static synchronized void bind(ReferenceRunner.Mode mode, Execution execution) {
        requireOwnerMode(mode);
        if (execution == null) {
            throw new IllegalArgumentException("the owner execution entry is null");
        }
        if (mode == ReferenceRunner.Mode.B_NATIVE_SERIAL) {
            serialBinding = execution;
        } else {
            parallelBinding = execution;
        }
    }

    /** Releases one owner entry and restores its by-name refusal. */
    public static synchronized void unbind(ReferenceRunner.Mode mode) {
        requireOwnerMode(mode);
        if (mode == ReferenceRunner.Mode.B_NATIVE_SERIAL) {
            serialBinding = null;
        } else {
            parallelBinding = null;
        }
    }

    /** Releases both owner entries so two independent runs cannot inherit a binding. */
    public static synchronized void unbindAll() {
        serialBinding = null;
        parallelBinding = null;
    }

    /** Whether one owner side currently holds a real execution entry. */
    public static boolean bound(ReferenceRunner.Mode mode) {
        return current(mode) != null;
    }

    /** The binding state of one owner side: bound or unbound. */
    public static String status(ReferenceRunner.Mode mode) {
        return bound(mode) ? "bound" : "unbound";
    }

    /** Whether one owner side is bound to this exact execution entry. */
    public static boolean boundTo(ReferenceRunner.Mode mode, Execution execution) {
        return current(mode) == execution;
    }

    /** The runner entry of one owner side; an unbound entry refuses by name. */
    public static ReferenceRunner.Entry adapter(ReferenceRunner.Mode mode) {
        requireOwnerMode(mode);
        return new OwnerAdapter(mode);
    }

    /** Runs one owner side with an explicit PASS or REFUSED status and a named reason. */
    public static ReferenceRunner.ModeResult runMode(ReferenceRunner.Mode mode, ReferenceInput input) {
        Objects.requireNonNull(input, "input");
        try {
            return new ReferenceRunner.ModeResult(mode, "PASS", adapter(mode).run(input), "");
        } catch (ReferenceRunner.ReferenceRefusal refusal) {
            return new ReferenceRunner.ModeResult(mode, "REFUSED", null, refusal.getMessage());
        } catch (Throwable failure) {
            return new ReferenceRunner.ModeResult(mode, "REFUSED", null,
                "execution-failed:" + failure.getClass().getSimpleName());
        }
    }

    /** Compares the two bound owner sides over one fixed input; unbound sides never pass. */
    public static Parity compare(ReferenceInput input) {
        Objects.requireNonNull(input, "input");
        Result serial = witness(ReferenceRunner.Mode.B_NATIVE_SERIAL, input);
        Result parallel = witness(ReferenceRunner.Mode.C_OWNER_PARALLEL, input);
        boolean same = serial != null && parallel != null && fieldsEqual(serial, parallel);
        return new Parity(same, same, serial, parallel, VANILLA_NOT_RUN, REQUIRED_FIELDS);
    }

    /** Compares the two bound owner sides over the legacy fixed-input shape. */
    public static Parity compare(Input input) {
        Objects.requireNonNull(input, "input");
        return compare(referenceOf(input));
    }

    /** The unmodified host reference has no entry in this build. */
    public static VanillaReference vanillaReference() {
        return VANILLA_NOT_RUN;
    }

    /** Whether every witness field of two results equals bit for bit. */
    public static boolean fieldsEqual(Result left, Result right) {
        if (left == null || right == null) {
            return false;
        }
        return left.streamPosition() == right.streamPosition()
            && left.publishedRevision() == right.publishedRevision()
            && left.state().equals(right.state())
            && left.poiOwner().equals(right.poiOwner())
            && left.events().equals(right.events())
            && left.inventory().equals(right.inventory())
            && left.manifest().equals(right.manifest())
            && left.digest().equals(right.digest());
    }

    /** FIXTURE: offline arithmetic serial input for the local runner, not an execution entry. */
    static Result serial(Input input) {
        List<Long> keys = sorted(input.keys());
        Map<Long, Long> state = new LinkedHashMap<>();
        Map<Long, Long> poi = new LinkedHashMap<>();
        Map<String, Long> inventory = new LinkedHashMap<>();
        List<String> events = new ArrayList<>();
        long streamPosition = 0L;
        long revision = 0L;
        for (long key : keys) {
            streamPosition += draws(input, key);
            state.put(key, key * 7L + 3L);
            poi.put(key, key % input.owners());
            inventory.put("item:" + key, key * 13L + 5L);
            events.add("apply:" + key);
            events.add("merge:" + key);
            revision = Math.max(revision, key * 2L);
        }
        return result(input, state, streamPosition, poi, events, inventory, revision);
    }

    /** FIXTURE: offline arithmetic parallel input for the local runner, not an execution entry. */
    static Result parallel(Input input) {
        Map<Long, Long> state = new LinkedHashMap<>();
        Map<Long, Long> poi = new LinkedHashMap<>();
        Map<String, Long> inventory = new LinkedHashMap<>();
        List<String> events = new ArrayList<>();
        long streamPosition = 0L;
        long revision = 0L;
        List<List<Long>> lanes = new ArrayList<>();
        for (int owner = 0; owner < input.owners(); owner++) {
            lanes.add(new ArrayList<>());
        }
        for (long key : sorted(input.keys())) {
            lanes.get((int) Math.floorMod(key, (long) input.owners())).add(key);
        }
        for (List<Long> lane : lanes) {
            for (long key : lane) {
                streamPosition += draws(input, key);
                state.put(key, key * 7L + 3L);
                poi.put(key, key % input.owners());
                inventory.put("item:" + key, key * 13L + 5L);
                events.add("apply:" + key);
                events.add("merge:" + key);
                revision = Math.max(revision, key * 2L);
            }
        }
        events.sort((left, right) -> {
            int byKey = Long.compare(eventKey(left), eventKey(right));
            return byKey != 0 ? byKey : eventName(left).compareTo(eventName(right));
        });
        return result(input, state, streamPosition, poi, events, inventory, revision);
    }

    private static Result witness(ReferenceRunner.Mode mode, ReferenceInput input) {
        try {
            return adapter(mode).run(input);
        } catch (Throwable refusal) {
            return null;
        }
    }

    private static ReferenceInput referenceOf(Input input) {
        List<ReferenceInput.Arrival> arrivals = new ArrayList<>();
        long order = 0L;
        for (long key : input.keys()) {
            arrivals.add(new ReferenceInput.Arrival(key, 0, 0.0, 0.0, 0.0, order++, 0L));
        }
        SharedStream stream = new SharedStream(StreamKey.of(input.worldSeed(), "qualification",
            "minecraft:overworld", "qualification", 0L));
        return new ReferenceInput(input.worldSeed(), "minecraft:overworld",
            input.registrationVersion(), 0L, input.owners(), arrivals,
            stream.snapshot(), stream.draws(), Map.of());
    }

    private static Execution current(ReferenceRunner.Mode mode) {
        return mode == ReferenceRunner.Mode.B_NATIVE_SERIAL ? serialBinding : parallelBinding;
    }

    private static void requireOwnerMode(ReferenceRunner.Mode mode) {
        if (mode != ReferenceRunner.Mode.B_NATIVE_SERIAL && mode != ReferenceRunner.Mode.C_OWNER_PARALLEL) {
            throw new IllegalArgumentException("only the serial and parallel owner sides are bindable");
        }
    }

    private static final class OwnerAdapter implements ReferenceRunner.Entry {

        private final ReferenceRunner.Mode mode;

        OwnerAdapter(ReferenceRunner.Mode mode) {
            this.mode = mode;
        }

        @Override
        public ReferenceRunner.Mode mode() {
            return mode;
        }

        @Override
        public Result run(ReferenceInput input) {
            Execution execution = current(mode);
            if (execution == null) {
                throw new ReferenceRunner.ReferenceRefusal(mode == ReferenceRunner.Mode.B_NATIVE_SERIAL
                    ? "native-serial-execution-unbound" : "owner-parallel-execution-unbound");
            }
            return execution.run(input);
        }
    }

    private static Result result(Input input, Map<Long, Long> state, long streamPosition,
                                 Map<Long, Long> poi, List<String> events,
                                 Map<String, Long> inventory, long revision) {
        Map<SaveManifestVector.Kind, Long> counts = new LinkedHashMap<>();
        counts.put(SaveManifestVector.Kind.CHUNK, (long) input.keys().size());
        counts.put(SaveManifestVector.Kind.ENTITY, (long) input.keys().size());
        counts.put(SaveManifestVector.Kind.POI, (long) poi.size());
        counts.put(SaveManifestVector.Kind.METADATA, 1L);
        counts.put(SaveManifestVector.Kind.SAVED_DATA, (long) inventory.size());
        SaveManifestVector.Manifest manifest = new SaveManifestVector(new SaveWatermarkLedger())
            .freeze(SaveManifestVector.EntryPoint.STOP, revision, 0L, counts).manifest();
        String digest = digest(state, streamPosition, poi, events, inventory, revision,
            manifest.counts());
        return new Result(Map.copyOf(state), streamPosition, Map.copyOf(poi), List.copyOf(events),
            Map.copyOf(inventory), revision, manifest.counts(), digest);
    }

    private static long draws(Input input, long key) {
        SharedStream stream = new SharedStream(StreamKey.of(input.worldSeed(), "qualification",
            "minecraft:overworld", "qualification", key));
        stream.nextLong();
        stream.nextLong();
        return stream.draws();
    }

    private static long eventKey(String event) {
        int colon = event.indexOf(':');
        try {
            return Long.parseLong(event.substring(colon + 1));
        } catch (NumberFormatException unavailable) {
            return Long.MAX_VALUE;
        }
    }

    private static String eventName(String event) {
        int colon = event.indexOf(':');
        return colon < 0 ? event : event.substring(0, colon);
    }

    private static List<Long> sorted(List<Long> keys) {
        List<Long> sorted = new ArrayList<>(new LinkedHashSet<>(keys));
        sorted.sort(Long::compare);
        return sorted;
    }

    private static String digest(Map<Long, Long> state, long streamPosition, Map<Long, Long> poi,
                                  List<String> events, Map<String, Long> inventory, long revision,
                                  Map<SaveManifestVector.Kind, Long> manifest) {
        StringBuilder text = new StringBuilder();
        new java.util.TreeMap<>(state).forEach((key, value) -> text.append("s").append(key)
            .append('=').append(value).append(';'));
        text.append("pos=").append(streamPosition).append(';');
        new java.util.TreeMap<>(poi).forEach((key, value) -> text.append("p").append(key)
            .append('=').append(value).append(';'));
        List<String> orderedEvents = new ArrayList<>(events);
        orderedEvents.sort(String::compareTo);
        for (String event : orderedEvents) {
            text.append("e").append(event).append(';');
        }
        new java.util.TreeMap<>(inventory).forEach((key, value) -> text.append("i").append(key)
            .append('=').append(value).append(';'));
        text.append("r=").append(revision).append(';');
        manifest.forEach((kind, value) -> text.append("m").append(kind).append('=')
            .append(value).append(';'));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception unavailable) {
            return text.toString();
        }
    }
}
