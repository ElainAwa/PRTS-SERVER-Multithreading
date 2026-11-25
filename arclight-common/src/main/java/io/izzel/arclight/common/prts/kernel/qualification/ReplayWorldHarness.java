/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueCapture;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.ServerThreadOwnerPhase;
import io.izzel.arclight.common.prts.kernel.random.SharedStreamTable;
import io.izzel.arclight.common.prts.kernel.random.StreamKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An opt-in, window-triggered replay over the frozen due window of one real server tick. It puts the
 * entities of that window back to the reversible state captured for the input, drives the host path
 * or the owner phase, and writes a structured witness report for the window to read. Every field is
 * either read from a live source or marked not measured with a reason; a missing source is never
 * turned into a zero, an empty map or a passing verdict.
 */
public final class ReplayWorldHarness {

    /** The property that names the report file the window reads. */
    public static final String REPORT_PROPERTY = "arclight.prts.diag.replayWorld.report";

    /** The property that names which reference this process drives. */
    public static final String MODE_PROPERTY = "arclight.prts.diag.replayWorld.mode";

    /** The eight witness fields, in canonical order. */
    public static final List<String> FIELDS = List.of("state", "streamPosition", "poiOwner",
        "events", "inventory", "publishedRevision", "manifest", "digest");

    /** The three references driven in sequence over the same input. */
    public enum Reference {
        A_HOST_SERIAL,
        B_NATIVE_SERIAL,
        C_OWNER_PARALLEL
    }

    /** One witness field: a normalized live value or a named not-measured reason. */
    public record Field(String name, boolean measured, String value, String reason) {

        public Field {
            Objects.requireNonNull(name, "name");
        }

        /** A field whose value came from a live source. */
        public static Field of(String name, String value) {
            return new Field(name, true, normalize(value), "");
        }

        /** A field whose source is absent; the reason is carried, never a fabricated value. */
        public static Field missing(String name, String reason) {
            return new Field(name, false, "", normalize(reason));
        }

        /** The single-line representation used in the report file. */
        public String rendered() {
            return measured ? value : "NOT_MEASURED:" + reason;
        }

        private static String normalize(String text) {
            String value = text == null ? "" : text.trim();
            return value.length() > 512 ? value.substring(0, 512) : value;
        }
    }

    /** One reference's eight fields plus any reset omissions named for that reference. */
    public record Witness(Reference reference, List<Field> fields) {

        public Witness {
            Objects.requireNonNull(reference, "reference");
            fields = List.copyOf(fields);
        }

        /** The field of the given name, or null when this witness does not carry it. */
        public Field field(String name) {
            for (Field field : fields) {
                if (field.name().equals(name)) {
                    return field;
                }
            }
            return null;
        }

        /** Whether every one of the eight fields was measured. */
        public boolean complete() {
            if (fields.size() != FIELDS.size()) {
                return false;
            }
            return fields.stream().allMatch(Field::measured);
        }
    }

    /** The whole replay report: reset accounting, three witnesses and the two verdicts. */
    public record Report(long tick, String world, String status, List<Field> reset,
                         List<Witness> witnesses, boolean bCExact, boolean full, List<String> notes) {

        public Report {
            reset = List.copyOf(reset);
            witnesses = List.copyOf(witnesses);
            notes = List.copyOf(notes);
        }

        /** A deterministic line-oriented encoding the window can read without a parser library. */
        public String encode() {
            StringBuilder out = new StringBuilder();
            out.append("format=ReplayWorldHarness/1").append('\n');
            out.append("tick=").append(tick).append('\n');
            out.append("world=").append(escape(world)).append('\n');
            out.append("status=").append(escape(status)).append('\n');
            out.append("full=").append(full).append('\n');
            out.append("bCExact=").append(bCExact).append('\n');
            out.append("reset=").append(reset.size()).append('\n');
            for (int i = 0; i < reset.size(); i++) {
                Field field = reset.get(i);
                out.append("reset.").append(i).append('=').append(line(field)).append('\n');
            }
            out.append("notes=").append(notes.size()).append('\n');
            for (int i = 0; i < notes.size(); i++) {
                out.append("note.").append(i).append('=').append(escape(notes.get(i))).append('\n');
            }
            out.append("witnesses=").append(witnesses.size()).append('\n');
            for (int i = 0; i < witnesses.size(); i++) {
                Witness witness = witnesses.get(i);
                out.append("witness.").append(i).append(".reference=").append(witness.reference())
                    .append('\n');
                out.append("witness.").append(i).append(".fields=").append(witness.fields().size())
                    .append('\n');
                for (int j = 0; j < witness.fields().size(); j++) {
                    out.append("witness.").append(i).append(".field.").append(j).append('=')
                        .append(line(witness.fields().get(j))).append('\n');
                }
            }
            return out.toString();
        }

        /** Parses an encoding; a malformed line is refused with the line number named. */
        public static DecodeResult decode(String text) {
            if (text == null) {
                return new DecodeResult(null, "line:0:null-report");
            }
            try {
                Codec codec = new Codec(text);
                if (!"ReplayWorldHarness/1".equals(codec.value("format"))) {
                    return new DecodeResult(null, "line:" + codec.lastLine() + ":format");
                }
                long tick = Long.parseLong(codec.value("tick"));
                String world = codec.value("world");
                String status = codec.value("status");
                boolean full = Boolean.parseBoolean(codec.value("full"));
                boolean bCExact = Boolean.parseBoolean(codec.value("bCExact"));
                int resetCount = Integer.parseInt(codec.value("reset"));
                if (resetCount < 0) {
                    return new DecodeResult(null, "line:" + codec.lastLine() + ":reset");
                }
                List<Field> reset = new ArrayList<>();
                for (int i = 0; i < resetCount; i++) {
                    reset.add(codec.field("reset." + i));
                }
                int noteCount = Integer.parseInt(codec.value("notes"));
                if (noteCount < 0) {
                    return new DecodeResult(null, "line:" + codec.lastLine() + ":notes");
                }
                List<String> notes = new ArrayList<>();
                for (int i = 0; i < noteCount; i++) {
                    notes.add(codec.value("note." + i));
                }
                int witnessCount = Integer.parseInt(codec.value("witnesses"));
                if (witnessCount < 0) {
                    return new DecodeResult(null, "line:" + codec.lastLine() + ":witnesses");
                }
                List<Witness> witnesses = new ArrayList<>();
                for (int i = 0; i < witnessCount; i++) {
                    Reference reference = Reference.valueOf(codec.value("witness." + i + ".reference"));
                    int fieldCount = Integer.parseInt(codec.value("witness." + i + ".fields"));
                    if (fieldCount < 0) {
                        return new DecodeResult(null, "line:" + codec.lastLine() + ":witness.fields");
                    }
                    List<Field> fields = new ArrayList<>();
                    for (int j = 0; j < fieldCount; j++) {
                        fields.add(codec.field("witness." + i + ".field." + j));
                    }
                    witnesses.add(new Witness(reference, fields));
                }
                Report report = new Report(tick, world, status, reset, witnesses, bCExact, full, notes);
                return new DecodeResult(report, "");
            } catch (RuntimeException invalid) {
                return new DecodeResult(null, "encode-invalid:" + invalid.getClass().getSimpleName());
            }
        }
    }

    /** The outcome of decoding a report. */
    public record DecodeResult(Report report, String failure) {

        public boolean ok() {
            return report != null && (failure == null || failure.isEmpty());
        }
    }

    /** The live objects the window supplies; nothing is installed or replaced by the harness. */
    public record Sources(ServerLevel level, SharedStreamTable streams) {

        public Sources {
            Objects.requireNonNull(level, "level");
        }
    }

    private static final String NM_POI = "no-owner-value-reader";
    private static final String NM_EVENTS = "no-structured-event-reader";
    private static final String NM_MANIFEST = "no-real-save-count-source";
    private static final String NM_A_KERNEL = "no-kernel-source-on-unmodified-host";
    private static final String NM_PUBLISH = "publish-contract-dormant:no-live-revision-reader";
    private static final String NM_RESET = "no-reversible-source-upstream";

    private ReplayWorldHarness() {
    }

    /**
     * Drives one reference from the frozen window of the given tick and writes the report to the
     * window-provided path. An absent window or a capture that refuses by name stops before any
     * entity is touched; the host path is never entered by the A reference.
     */
    public static Report run(long tick, Sources sources, Reference reference, Path reportPath) {
        Report report = drive(tick, sources, reference);
        write(report, reportPath);
        return report;
    }

    /** Drives the three references in order, each from a fresh restore of the same input. */
    public static Report runAll(long tick, Sources sources, Path reportPath) {
        List<Witness> witnesses = new ArrayList<>();
        List<Field> reset = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String world = "";
        for (Reference reference : Reference.values()) {
            Report one = drive(tick, sources, reference);
            if ("REFUSED".equals(one.status())) {
                Report refused = new Report(tick, world, "REFUSED", one.reset(), witnesses,
                    false, false, one.notes());
                write(refused, reportPath);
                return refused;
            }
            witnesses.addAll(one.witnesses());
            if (reset.isEmpty()) {
                reset.addAll(one.reset());
            }
            notes.addAll(one.notes());
            world = one.world();
        }
        Report combined = new Report(tick, world, "COMPLETED", reset, witnesses, false, false,
            notes);
        write(combined, reportPath);
        return combined;
    }

    private static Report drive(long tick, Sources sources, Reference reference) {
        EntityDueCapture.Window window = EntityDueCapture.current(tick);
        if (window == null) {
            return refused(tick, null, "due-window-absent", null);
        }
        // The reference business stream must exist in the real table before capture: the record
        // names one stream by world seed and world id, and a missing stream refuses by name.
        String worldId = window.worlds().isEmpty() ? "" : window.worlds().get(0).worldId();
        if (!worldId.isEmpty()) {
            sources.streams().stream(StreamKey.of(sources.level().getSeed(), worldId, worldId,
                "reference", 0L));
        }
        ReferenceCapture.Result captured = ReferenceCapture.fromDueWindow(window, tick,
            ReferenceCapture.fromLevel(sources.level(), sources.streams()));
        if (!captured.bound()) {
            return refused(tick, null, captured.unboundReason(), null);
        }
        ReferenceInput input = captured.input();
        List<Field> reset = restore(sources.level(), input);
        List<String> notes = new ArrayList<>();
        try {
            if (reference == Reference.A_HOST_SERIAL) {
                notes.add("A-host-serial:owner-enter-not-called");
            } else {
                ServerThreadOwnerPhase.enter(sources.level(), window);
                ServerThreadOwnerPhase.finishDeferred();
                ServerThreadOwnerPhase.endTick(sources.level());
                notes.add(reference.name() + ":owner-mode-from-process-properties");
            }
        } catch (Throwable failure) {
            notes.add("drive-failed:" + failure.getClass().getSimpleName());
        }
        Witness witness = witness(sources, input, reference);
        Report report = new Report(tick, input.worldId(), "COMPLETED", reset, List.of(witness),
            false, false, notes);
        return report;
    }

    /** The mode named by the window property, or null when the reference is not declared. */
    public static Reference configuredReference() {
        String raw = System.getProperty(MODE_PROPERTY, "");
        for (Reference candidate : Reference.values()) {
            if (candidate.name().equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        return null;
    }

    /** The canonical digest of one witness over its measured fields; absent fields are omitted. */
    public static String digest(Witness witness) {
        StringBuilder text = new StringBuilder();
        for (String name : FIELDS) {
            Field field = witness.field(name);
            if (field != null && field.measured()) {
                text.append(name).append('=').append(field.value()).append(';');
            }
        }
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception unavailable) {
            return "NOT_MEASURED:sha256-unavailable";
        }
    }

    private static List<Field> restore(ServerLevel level, ReferenceInput input) {
        List<Field> fields = new ArrayList<>();
        int restored = 0;
        for (Map.Entry<Long, ReferenceInput.EntityState> entry : input.reversibleState().entrySet()) {
            int id = Math.toIntExact(entry.getKey());
            Entity entity = level.getEntity(id);
            ReferenceInput.EntityState state = entry.getValue();
            if (entity == null) {
                continue;
            }
            entity.setPos(state.x(), state.y(), state.z());
            entity.setYRot((float) state.yRot());
            entity.setXRot((float) state.xRot());
            entity.setYBodyRot((float) state.yBodyRot());
            entity.setDeltaMovement(state.vx(), state.vy(), state.vz());
            entity.tickCount = state.tickCount();
            restored++;
        }
        fields.add(Field.of("reset.poseAndVelocity", String.valueOf(restored)));
        fields.add(Field.of("reset.tickCount", String.valueOf(restored)));
        fields.add(Field.missing("reset.entityRng", NM_RESET));
        fields.add(Field.missing("reset.brain", NM_RESET));
        fields.add(Field.missing("reset.goals", NM_RESET));
        fields.add(Field.missing("reset.inventory", NM_RESET));
        fields.add(Field.missing("reset.worldClock", NM_RESET));
        return fields;
    }

    private static Witness witness(Sources sources, ReferenceInput input, Reference reference) {
        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put("state", Field.of("state", stateOf(sources.level(), input)));
        boolean host = reference == Reference.A_HOST_SERIAL;
        fields.put("streamPosition", host ? Field.missing("streamPosition", NM_A_KERNEL)
            : Field.of("streamPosition", String.valueOf(input.stream().draws())));
        fields.put("poiOwner", Field.missing("poiOwner", NM_POI));
        fields.put("events", Field.missing("events", NM_EVENTS));
        fields.put("inventory", Field.of("inventory", inventoryOf(sources.level(), input)));
        fields.put("publishedRevision", Field.missing("publishedRevision", NM_PUBLISH));
        fields.put("manifest", Field.missing("manifest", NM_MANIFEST));
        boolean allOthers = fields.values().stream().allMatch(Field::measured);
        fields.put("digest", allOthers
            ? Field.of("digest", digest(new Witness(reference, List.copyOf(fields.values()))))
            : Field.missing("digest", "inputs-not-all-measured"));
        return new Witness(reference, List.copyOf(fields.values()));
    }

    private static String stateOf(ServerLevel level, ReferenceInput input) {
        StringBuilder text = new StringBuilder();
        for (Long id : input.reversibleState().keySet()) {
            Entity entity = level.getEntity(Math.toIntExact(id));
            if (entity == null) {
                continue;
            }
            text.append(id).append(':').append(entity.getX()).append(',').append(entity.getY())
                .append(',').append(entity.getZ()).append(',').append(entity.getYRot())
                .append(',').append(entity.getXRot()).append(',').append(entity.tickCount)
                .append(';');
        }
        return text.toString();
    }

    private static String inventoryOf(ServerLevel level, ReferenceInput input) {
        StringBuilder text = new StringBuilder();
        for (Long id : input.reversibleState().keySet()) {
            Entity entity = level.getEntity(Math.toIntExact(id));
            if (!(entity instanceof Villager villager)) {
                continue;
            }
            text.append(id).append(':');
            var inventory = villager.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot);
                text.append(stack.isEmpty() ? "-" : stack.getCount()).append(',');
            }
            text.append(';');
        }
        return text.toString();
    }

    private static Report refused(long tick, String world, String reason, Path reportPath) {
        Report report = new Report(tick, world == null ? "" : world, "REFUSED", List.of(),
            List.of(), false, false, List.of(reason));
        write(report, reportPath);
        return report;
    }

    private static void write(Report report, Path reportPath) {
        if (reportPath == null) {
            return;
        }
        try {
            Path parent = reportPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reportPath, report.encode(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The returned report still carries the witness; a write failure is not a passing run.
        }
    }

    private static String line(Field field) {
        return field.name() + '\t' + field.measured() + '\t' + escape(field.value()) + '\t'
            + escape(field.reason());
    }

    private static String escape(String text) {
        String value = text == null ? "" : text;
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                out.append(switch (c) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> c;
                });
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final class Codec {

        private final Map<String, String> entries = new LinkedHashMap<>();
        private int lastLine;

        Codec(String text) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                lastLine = i + 1;
                if (lines[i].isEmpty()) {
                    continue;
                }
                int separator = lines[i].indexOf('=');
                if (separator < 0) {
                    throw new IllegalArgumentException("no-separator");
                }
                entries.put(lines[i].substring(0, separator), lines[i].substring(separator + 1));
            }
        }

        String value(String key) {
            String raw = entries.get(key);
            if (raw == null) {
                throw new IllegalArgumentException("missing:" + key);
            }
            return unescape(raw);
        }

        Field field(String prefix) {
            String raw = entries.get(prefix);
            if (raw == null) {
                throw new IllegalArgumentException("missing:" + prefix);
            }
            String[] parts = raw.split("\t", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("field-parts:" + prefix);
            }
            return new Field(parts[0], Boolean.parseBoolean(parts[1]),
                unescape(parts[2]), unescape(parts[3]));
        }

        int lastLine() {
            return lastLine;
        }
    }
}
