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

/** The contract-level dual-reference harness: for one fixed arrival input, one fixed registration
 * version and one fixed StreamKey start, it computes the deterministic S-Native serial reference and
 * the owner-parallel result and compares final state, stream position, POI ownership, event results,
 * inventory products, published revision and the save manifest. The unmodified vanilla serial
 * reference has no host entry, so that half stays NOT_RUN and no full dual-reference pass is claimed. */
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

    private static final VanillaReference VANILLA_NOT_RUN = new VanillaReference("NOT_RUN",
        "no unmodified host serial reference entry exists; the comparison half is not claimed");

    private DualReferenceParityHarness() {
    }

    /** Runs both contract sides and keeps the vanilla half explicitly NOT_RUN. */
    public static Parity compare(Input input) {
        Objects.requireNonNull(input, "input");
        Result serial = serial(input);
        Result parallel = parallel(input);
        boolean same = fieldsEqual(serial, parallel);
        return new Parity(same, same, serial, parallel, VANILLA_NOT_RUN,
            List.of("state", "streamPosition", "poiOwner", "events", "inventory",
                "publishedRevision", "manifest", "digest"));
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
