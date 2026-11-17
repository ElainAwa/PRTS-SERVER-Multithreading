/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk.save;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The save manifest vector frozen at the linearization point: every save-all, stop and unload
 * entry names the chunk, entity, POI, metadata and saved-data counts it froze; an incomplete freeze
 * names the missing categories instead of filling zeros, and a restart readback only verifies a
 * manifest whose every count came back. */
public final class SaveManifestVector {

    /** The categories one manifest carries. */
    public enum Kind {
        CHUNK,
        ENTITY,
        POI,
        METADATA,
        SAVED_DATA
    }

    /** The three entry points that must emit a complete manifest. */
    public enum EntryPoint {
        SAVE_ALL,
        STOP,
        UNLOAD
    }

    /** One frozen vector. */
    public record Manifest(EntryPoint entryPoint, long revision, long linearizationTick,
                           Map<Kind, Long> counts) {
    }

    /** A freeze that could not name every category; it carries the missing ones, never zeros. */
    public record Frozen(Manifest manifest, List<Kind> missing) {

        public boolean complete() {
            return missing.isEmpty();
        }
    }

    private static final long VERIFY_CHUNK = 0L;
    private final SaveWatermarkLedger ledger;
    private long frozen;
    private long incomplete;
    private long verified;
    private long mismatched;

    public SaveManifestVector(SaveWatermarkLedger ledger) {
        this.ledger = ledger;
    }

    /** Freezes the vector of one entry point; a missing category is reported, never invented. */
    public synchronized Frozen freeze(EntryPoint entryPoint, long revision, long tick,
                                      Map<Kind, Long> counts) {
        List<Kind> missing = new ArrayList<>();
        Map<Kind, Long> frozenCounts = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            Long count = counts.get(kind);
            if (count == null || count < 0L) {
                missing.add(kind);
            } else {
                frozenCounts.put(kind, count);
            }
        }
        frozen++;
        if (!missing.isEmpty()) {
            incomplete++;
            return new Frozen(new Manifest(entryPoint, revision, tick, Map.copyOf(frozenCounts)),
                List.copyOf(missing));
        }
        return new Frozen(new Manifest(entryPoint, revision, tick, Map.copyOf(frozenCounts)),
            List.of());
    }

    /** Reads a persisted manifest back and verifies the durable chunk; a mismatch never verifies. */
    public synchronized boolean verifyRestart(Manifest expected, Map<Kind, Long> readback) {
        boolean matches = true;
        for (Kind kind : Kind.values()) {
            Long expectedCount = expected.counts().get(kind);
            Long actual = readback.get(kind);
            if (expectedCount == null || !expectedCount.equals(actual)) {
                matches = false;
            }
        }
        if (!matches) {
            mismatched++;
            ledger.fail(VERIFY_CHUNK, SaveWatermarkLedger.Failure.IO_ERROR);
            return false;
        }
        boolean verifiedNow = ledger.restartVerified(VERIFY_CHUNK, true);
        if (verifiedNow) {
            verified++;
        }
        return verifiedNow;
    }

    /** Writes one manifest to a plain text file for the restart readback fixture. */
    public static void write(Path file, Manifest manifest) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("entry=").append(manifest.entryPoint()).append(System.lineSeparator());
        text.append("revision=").append(manifest.revision()).append(System.lineSeparator());
        for (Map.Entry<Kind, Long> count : manifest.counts().entrySet()) {
            text.append(count.getKey()).append('=').append(count.getValue())
                .append(System.lineSeparator());
        }
        Files.writeString(file, text.toString());
    }

    /** Reads one manifest text back into its category counts. */
    public static Map<Kind, Long> read(Path file) throws IOException {
        Map<Kind, Long> counts = new EnumMap<>(Kind.class);
        for (String line : Files.readAllLines(file)) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            try {
                counts.put(Kind.valueOf(key), Long.parseLong(value));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        return counts;
    }

    public synchronized long frozen() {
        return frozen;
    }

    public synchronized long incomplete() {
        return incomplete;
    }

    public synchronized long verified() {
        return verified;
    }

    public synchronized long mismatched() {
        return mismatched;
    }
}
