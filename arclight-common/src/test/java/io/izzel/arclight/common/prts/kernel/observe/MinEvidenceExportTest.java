/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixture obligations of the minimal-evidence group: the size gate and its metadata-only
 * answer, the four state semantics, the three limits and the first-limit rule, the version-1 wire
 * round trip with its refusals, the not-comparable rule and the separation from the seal path.
 * Nothing here starts an instance: the tables are built in process and the guard is a real one. */
class MinEvidenceExportTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER_WORLD = "minecraft:the_nether";
    private static final String DOMAIN = "chunk.publish";
    private static final String STATUS = "PUBLISHED";
    private static final long PLAN = 11L;
    private static final FrozenDeclarations.FreezeKey FREEZE =
        new FrozenDeclarations.FreezeKey("lifecycle-7", 3L);
    private static final String[] HEADER_FIELDS = {"payloadVersion", "requestedFreeze",
        "requestedPlan", "returnedFreeze", "returnedPlan", "state", "context", "available",
        "scanned", "emitted", "truncated"};
    private static final String PROVEN = "PROVEN";
    private static final String UNPROVEN = "UNPROVEN";
    private static final String INCONSISTENT = "INCONSISTENT";
    private static final String END = "end";

    @Test
    void theOverThresholdAnswerIsMetadataOnlyWithItsOwnAvailable() {
        FrozenDeclarations justOver = table(KernelReadings.SEAT_COPY_THRESHOLD + 1, 1L, 0);
        FrozenDeclarations atScale = table(100_000, 1L, 0);
        long smallStarted = System.nanoTime();
        List<String> small = dump(justOver, pairedEpochs(), PROVEN);
        long smallMillis = (System.nanoTime() - smallStarted) / 1_000_000L;
        long largeStarted = System.nanoTime();
        List<String> large = dump(atScale, pairedEpochs(), PROVEN);
        long largeMillis = (System.nanoTime() - largeStarted) / 1_000_000L;
        Map<String, String> first = header(small);
        Map<String, String> second = header(large);
        // The decision and the shape are what the two answers share, and each prints the size of its
        // own table: available is the true size and the sampling instants differ, so an expectation
        // of two byte-identical answers would be an expectation no honest answer can meet.
        assertEquals(first.keySet(), second.keySet());
        assertEquals("CURRENT", first.get("requestedFreeze"));
        assertEquals("-1", first.get("requestedPlan"));
        assertEquals("65", first.get("available"));
        assertEquals("100000", second.get("available"));
        assertEquals("0", first.get("scanned"));
        assertEquals("0", first.get("emitted"));
        assertEquals("1", first.get("truncated"));
        assertEquals(first.get("scanned"), second.get("scanned"));
        assertEquals(first.get("emitted"), second.get("emitted"));
        assertEquals(first.get("truncated"), second.get("truncated"));
        assertEquals(first.get("state"), second.get("state"));
        assertEquals(List.of(), rows(small));
        assertEquals(List.of(), rows(large));
        assertNotEquals(small, large, "the two answers differ in available, and never claim otherwise");
        // Elapsed time is auxiliary evidence beside the shape above, not the gate: a copy-then-
        // truncate implementation would write the same short answer, which is why the review fixed
        // the shape as the evidence and the call itself as a control-flow question.
        assertTrue(smallMillis < 1000L, "the over-threshold render took " + smallMillis + " ms");
        assertTrue(largeMillis < 1000L, "the over-threshold render took " + largeMillis + " ms");
        System.out.println("MIN_EVIDENCE_OVER_THRESHOLD\tsmall_ms=" + smallMillis + "\tlarge_ms="
            + largeMillis + "\tsmall_rows=" + rows(small).size() + "\tlarge_rows="
            + rows(large).size());
    }

    @Test
    void aTableWithinTheThresholdIsDeliveredComplete() {
        List<String> dump = dump(table(3, 1L, 0), pairedEpochs(), PROVEN);
        Map<String, String> fields = header(dump);
        assertEquals("3", fields.get("available"));
        assertEquals("3", fields.get("scanned"));
        assertEquals("3", fields.get("emitted"));
        assertEquals("0", fields.get("truncated"));
        assertEquals(3, rows(dump).size(), "no other limit had been touched yet");
        assertEquals(List.of("PAIRED", "PAIRED", "PAIRED"), verdicts(dump));
    }

    @Test
    void theByteLimitStopsTheDumpBeforeTheRowLimit() {
        // Every row spends about three hundred bytes, so the byte budget runs out inside a table of
        // sixty-four entries: the row limit is not what stopped it, and no row is cut, so the only
        // limit this case touched is the byte one.
        List<String> dump = dump(table(64, 1L, 200), pairedEpochs(), PROVEN);
        Map<String, String> fields = header(dump);
        assertEquals("64", fields.get("available"));
        assertEquals("1", fields.get("truncated"), "the byte limit was the limit this case touched");
        int emitted = Integer.parseInt(fields.get("emitted"));
        assertTrue(emitted > 0 && emitted < 64, "the dump stopped while rows were still due: "
            + emitted);
        assertEquals(emitted, rows(dump).size(), "every delivered row is a complete row");
        int scanned = Integer.parseInt(fields.get("scanned"));
        assertTrue(scanned <= 64);
        assertEquals(emitted + 1, scanned,
            "scanned counts the work really done, so it names the row that no longer fitted");
        System.out.println("MIN_EVIDENCE_BYTE_LIMIT\tavailable=" + fields.get("available")
            + "\tscanned=" + scanned + "\temitted=" + emitted + "\ttruncated="
            + fields.get("truncated") + "\trows=" + rows(dump).size()
            + "\tlimit=bytes\tother_limits_touched=0");
    }

    @Test
    void aComponentThatCannotBeSentIsNotComparableAndNeverPaired() {
        List<String> tooLong = dump(table(2, 1L, 600), pairedEpochs(), PROVEN);
        List<String> loneSurrogate = dump(table(1, 1L, 0, "\uD800"), pairedEpochs(), PROVEN);
        for (List<String> dump : List.of(tooLong, loneSurrogate)) {
            Map<String, String> fields = header(dump);
            assertEquals("1", fields.get("truncated"));
            assertTrue(Integer.parseInt(fields.get("emitted")) < Integer.parseInt(
                fields.get("available")), "a cut row is not a complete row");
            assertTrue(verdicts(dump).contains("NOT_COMPARABLE"), "the cut identity is named");
            assertFalse(verdicts(dump).contains("PAIRED"), "a cut identity is never paired");
        }
        assertEquals(1, rows(loneSurrogate).size(), "the row is written with its cut named");
        assertEquals(List.of("NOT_COMPARABLE"), verdicts(loneSurrogate));
        System.out.println("MIN_EVIDENCE_NOT_COMPARABLE\tavailable="
            + header(loneSurrogate).get("available") + "\temitted="
            + header(loneSurrogate).get("emitted") + "\ttruncated="
            + header(loneSurrogate).get("truncated") + "\tverdict=" + verdicts(loneSurrogate)
            + "\tpaired=0");
    }

    @Test
    void anUnprovenContextAnswersNoVerdictAtAll() {
        for (String context : List.of(UNPROVEN, INCONSISTENT)) {
            List<String> dump = dump(table(2, 1L, 0), pairedEpochs(), context);
            assertEquals(context, header(dump).get("context"));
            assertEquals(List.of("NOT_COMPARABLE", "NOT_COMPARABLE"), verdicts(dump),
                "an unprovable context answers no positive");
        }
        assertEquals(2, rows(dump(table(2, 1L, 0), pairedEpochs(), PROVEN)).size());
    }

    @Test
    void theEpochVerdictsFollowTheEqualityRule() {
        assertEquals(List.of("PAIRED"), verdicts(dump(table(1, 1L, 0), pairedEpochs(), PROVEN)));
        assertEquals(List.of("EPOCH_DRIFT"),
            verdicts(dump(table(1, 1L, 0), driftedEpochs(), PROVEN)));
        assertEquals(List.of("UNTRACKED"),
            verdicts(dump(table(1, 1L, 0), new WorldEpochs(), PROVEN)));
        assertEquals(List.of("UNLIVE"), verdicts(dump(table(1, 1L, 0), lostEpochs(), PROVEN)));
    }

    @Test
    void theWireFormatRoundTripsEverythingItCanSend() {
        List<String> dump = dump(table(4, 1L, 0, "a|b#c=d,e", "line\nbreak", "back\\slash",
            "\uD834\uDD1E"), pairedEpochs(), PROVEN);
        List<String> delivered = rows(dump);
        assertEquals(4, delivered.size());
        assertEquals(List.of("a|b#c=d,e", "line\nbreak", "back\\slash", "\uD834\uDD1E"),
            List.of(decode(delivered.get(0)).get(4), decode(delivered.get(1)).get(4),
                decode(delivered.get(2)).get(4), decode(delivered.get(3)).get(4)),
            "the components come back as themselves");
        assertTrue(delivered.get(0).startsWith("row=P"), "a separator is data and needs no escape");
        assertTrue(delivered.get(1).startsWith("row=E"));
        assertTrue(delivered.get(2).startsWith("row=E"));
        assertTrue(delivered.get(2).contains("\\\\"), "the escape character escapes itself");
        assertTrue(delivered.get(3).contains("4:\uD834\uDD1E"),
            "a character outside the basic plane is one component of four bytes, not two halves");
    }

    @Test
    void theReaderRefusesWhatItCannotRead() {
        List<String> good = dump(table(1, 1L, 0), pairedEpochs(), PROVEN);
        assertFalse(rows(good).isEmpty());
        assertEquals(11, decode(rows(good).get(0)).size());
        // An unknown version is refused rather than parsed as version 1, and never partially.
        assertNull(read(replaceHeader(good, "payloadVersion", "2")));
        // A dump whose end marker is gone is malformed rather than complete.
        assertNull(read(good.subList(0, good.size() - 1)));
        assertEquals(List.of(), read(List.of(good.get(0), END)),
            "a complete dump of an empty table is read as no row, not as a malformed one");
        List<String> trailing = new ArrayList<>(good);
        trailing.add("junk");
        assertNull(read(trailing), "a dump with trailing bytes after its end marker is refused");
        // A declared length that runs past the line, and a row that lost a component: both malformed.
        assertNull(decode("row=P3:abc", 11), "a declared length that runs past the line is refused");
        assertNull(decode("row=P" + encode(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")),
            11), "a row that lost a component is malformed and is never completed from the header");
        // The empty component is read as empty, and broken UTF-8 or a lone surrogate is refused
        // instead of being replaced by a guess.
        assertEquals("", decode("row=P0:" + "1:x".repeat(10)).get(0));
        assertNull(strictBytes(new byte[] {'1', ':', (byte) 0xFF}), "broken UTF-8 is refused");
        assertNull(strict("3:\uD800"), "a lone surrogate is refused rather than replaced");
    }

    @Test
    void theOwnerThreadIdIsDerivedFromTheBoundReference() {
        WorldWriteGuard guard = new WorldWriteGuard(null, null, null, null, null);
        assertEquals(CommitNamespaces.NO_THREAD, guard.serverThreadId(),
            "a guard with no bound thread answers the sentinel and never a thread id");
        guard.bindServerThread(Thread.currentThread(), "test-site");
        assertEquals(Thread.currentThread().threadId(), guard.serverThreadId());
        Thread other = new Thread(() -> { });
        guard.bindServerThread(other, "test-site");
        assertEquals(other.threadId(), guard.serverThreadId());
        assertFalse(guard.onServerThread(),
            "the derived id and the guard test read the same bound reference");
    }

    @Test
    void theGroupIsRenderedForTheCommandFaceAndNotForTheSealPath() {
        KernelModule module = KernelModule.instance();
        List<String> shared = KernelReadings.export(module);
        List<String> forensic = KernelReadings.forensicReadings(module);
        // The keys are compared and not the values: a counter or a timer can move between two
        // renders of one module, and a key the group added would be present in both of them.
        assertEquals(keysOf(shared), keysOf(forensic.subList(0, shared.size())),
            "the seal rendering keeps its keys inside the command rendering");
        assertTrue(forensic.size() > shared.size(), "the command face carries the group");
        for (String key : new String[] {"product.commit.owner_thread_bound_id",
            "product.commit.declaration_seats", "product.commit.run_identity"}) {
            assertFalse(joined(shared).contains(key), key + " reached the shared rendering");
            assertTrue(joined(forensic).contains(key), key + " is missing from the group");
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : shared) {
            int at = line.indexOf('=');
            if (at > 0) {
                expected.put(line.substring(0, at), line.substring(at + 1));
            }
        }
        // The keys are what the separation is about: a value can move between two renders of the
        // same module, and a key that the group added would be there in both of them.
        assertEquals(expected.keySet(), CloseSealWriter.Snapshot.capture("C1", List.of(WORLD), module)
            .readings().keySet(), "the seal snapshot reads the shared rendering and nothing else");
    }

    @Test
    void theAnswerDerivesFromOneTableReferenceAndReservesTheOtherStates() {
        FrozenDeclarations table = table(2, 1L, 0);
        Map<String, String> fields = header(dump(table, pairedEpochs(), PROVEN));
        assertEquals("lifecycle-7#3", fields.get("returnedFreeze"));
        assertEquals(Long.toString(PLAN), fields.get("returnedPlan"));
        assertEquals("CURRENT", fields.get("requestedFreeze"));
        assertEquals("-1", fields.get("requestedPlan"));
        for (String reserved : new String[] {"SNAPSHOT_NOT_RETAINED", "FOREIGN_LIFECYCLE",
            "DIAGNOSTIC_TABLE"}) {
            assertFalse(joined(dump(table, pairedEpochs(), PROVEN)).contains(reserved),
                reserved + " is reserved in this version");
        }
        assertEquals(table.planSequence(), PLAN);
        assertEquals(table.freeze(), FREEZE);
    }

    @Test
    void anEmptyTableAnswersANamedStateAndNoRow() {
        for (String state : List.of("NOT_WIRED", "NO_DECLARATIONS", "REFUSED")) {
            List<String> dump = dump(FrozenDeclarations.none(), pairedEpochs(), PROVEN, state);
            Map<String, String> fields = header(dump);
            System.out.println("MIN_EVIDENCE_NAMED_STATE\tstate=" + state + "\tavailable="
                + fields.get("available") + "\tscanned=" + fields.get("scanned") + "\temitted="
                + fields.get("emitted") + "\ttruncated=" + fields.get("truncated") + "\trows="
                + rows(dump).size());
            assertEquals(state, fields.get("state"));
            assertEquals("0", fields.get("available"));
            assertEquals("0", fields.get("scanned"));
            assertEquals("0", fields.get("emitted"));
            assertEquals(List.of(), rows(dump));
            assertEquals(END, dump.get(dump.size() - 1));
        }
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    private static List<String> keysOf(List<String> lines) {
        List<String> found = new ArrayList<>();
        for (String line : lines) {
            int at = line.indexOf('=');
            found.add(at > 0 ? line.substring(0, at) : line);
        }
        return found;
    }

    private static List<String> dump(FrozenDeclarations table, WorldEpochs epochs, String context) {
        return dump(table, epochs, context, table.state());
    }

    private static List<String> dump(FrozenDeclarations table, WorldEpochs epochs, String context,
                                     String state) {
        List<String> lines = new ArrayList<>();
        KernelReadings.seatDump(lines, table, epochs, state, context);
        return lines;
    }

    private static WorldEpochs pairedEpochs() {
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of(WORLD));
        return epochs;
    }

    private static WorldEpochs driftedEpochs() {
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of(OTHER_WORLD));
        epochs.observe(List.of(WORLD));
        return epochs;
    }

    private static WorldEpochs lostEpochs() {
        WorldEpochs epochs = pairedEpochs();
        epochs.observe(List.of(OTHER_WORLD));
        return epochs;
    }

    private static FrozenDeclarations table(int count, long frozenEpoch, int nodePad) {
        return table(count, frozenEpoch, nodePad, null, null, null, null);
    }

    private static FrozenDeclarations table(int count, long frozenEpoch, int nodePad,
                                            String firstNode) {
        return table(count, frozenEpoch, nodePad, firstNode, null, null, null);
    }

    private static FrozenDeclarations table(int count, long frozenEpoch, int nodePad, String firstNode,
                                            String secondNode, String thirdNode, String fourthNode) {
        String[] named = {firstNode, secondNode, thirdNode, fourthNode};
        List<FrozenDeclarations.Entry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String node = index < named.length && named[index] != null ? named[index]
                : "node-" + index + "x".repeat(nodePad);
            entries.add(new FrozenDeclarations.Entry(new FrozenDeclarations.Seat(WORLD, DOMAIN, PLAN,
                index), node, index + 1, index + 1L, STATUS, index + 5L, frozenEpoch));
        }
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.of(FREEZE, PLAN, entries);
        assertTrue(sealed.sealed(), "the fixture table has to be a table a seal could have written: "
            + sealed.refusal());
        return sealed.declarations();
    }

    private static Map<String, String> header(List<String> dump) {
        String header = dump.get(0);
        assertTrue(header.startsWith("product.commit.declaration_seats=begin "), header);
        // The header fields are the format's own tokens and come back by their byte lengths alone:
        // the escape discipline belongs to the row components, which carry values a seat names.
        List<String> fields = strict(header.substring(header.indexOf("begin ") + "begin ".length()));
        assertEquals(HEADER_FIELDS.length, fields.size(), "the header carries the declared fields");
        Map<String, String> named = new LinkedHashMap<>();
        for (int index = 0; index < HEADER_FIELDS.length; index++) {
            named.put(HEADER_FIELDS[index], fields.get(index));
        }
        return named;
    }

    private static List<String> rows(List<String> dump) {
        List<String> found = new ArrayList<>();
        for (int index = 1; index < dump.size(); index++) {
            if (dump.get(index).startsWith("row=")) {
                found.add(dump.get(index));
            }
        }
        return found;
    }

    private static List<String> verdicts(List<String> dump) {
        List<String> found = new ArrayList<>();
        for (String row : rows(dump)) {
            List<String> components = decode(row);
            found.add(components.get(components.size() - 1));
        }
        return found;
    }

    /** The reader side of the fixture: a known version, the declared number of components, a marker
     * that is P or E, and a terminating end marker, or the dump is malformed and is never parsed in
     * part. */
    private static List<List<String>> read(List<String> dump) {
        if (dump.size() < 2 || !END.equals(dump.get(dump.size() - 1))) {
            return null;
        }
        Map<String, String> fields = header(dump);
        if (!"1".equals(fields.get("payloadVersion"))) {
            return null;
        }
        List<List<String>> decoded = new ArrayList<>();
        for (int index = 1; index < dump.size() - 1; index++) {
            List<String> components = decode(dump.get(index));
            if (components == null || components.size() != HEADER_FIELDS.length) {
                return null;
            }
            decoded.add(components);
        }
        return decoded;
    }

    private static List<String> replaceHeader(List<String> dump, String field, String value) {
        String header = dump.get(0);
        List<String> fields = strict(header.substring(header.indexOf("begin ") + "begin ".length()));
        fields.set(List.of(HEADER_FIELDS).indexOf(field), value);
        List<String> replaced = new ArrayList<>(dump);
        replaced.set(0, "product.commit.declaration_seats=begin " + encode(fields));
        return replaced;
    }

    private static List<String> decode(String row) {
        return decode(row, HEADER_FIELDS.length);
    }

    /** Splits one row line at its byte lengths and then unescapes: the length decides where a
     * component ends, and the value is only read after that. */
    private static List<String> decode(String row, int expected) {
        if (!row.startsWith("row=") || row.length() < 5) {
            return null;
        }
        char marker = row.charAt(4);
        if (marker != 'P' && marker != 'E') {
            return null;
        }
        List<String> fields = strict(row.substring(5));
        if (fields == null || fields.size() != expected) {
            return null;
        }
        if (marker == 'P') {
            return fields;
        }
        List<String> unescaped = new ArrayList<>();
        for (String field : fields) {
            String value = unescape(field);
            if (value == null) {
                return null;
            }
            unescaped.add(value);
        }
        return unescaped;
    }

    private static String encode(List<String> fields) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                text.append(' ');
            }
            String field = fields.get(index);
            text.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
        }
        return text.toString();
    }

    private static List<String> strict(String encoded) {
        return strictBytes(encoded.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> strictBytes(byte[] bytes) {
        List<String> fields = new ArrayList<>();
        int at = 0;
        while (at < bytes.length) {
            while (at < bytes.length && bytes[at] == ' ') {
                at++;
            }
            if (at >= bytes.length) {
                break;
            }
            int colon = at;
            while (colon < bytes.length && bytes[colon] != ':') {
                colon++;
            }
            if (colon == at || colon == bytes.length) {
                return null;
            }
            int length;
            try {
                length = Integer.parseInt(new String(bytes, at, colon - at, StandardCharsets.US_ASCII));
            } catch (NumberFormatException error) {
                return null;
            }
            if (length < 0 || colon + 1 + length > bytes.length) {
                return null;
            }
            byte[] payload = new byte[length];
            System.arraycopy(bytes, colon + 1, payload, 0, length);
            String value = new String(payload, StandardCharsets.UTF_8);
            if (!Arrays.equals(payload, value.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            fields.add(value);
            at = colon + 1 + length;
        }
        return fields;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                out.append(character);
                continue;
            }
            if (index + 1 >= value.length()) {
                return null;
            }
            char next = value.charAt(++index);
            if (next == '\\') {
                out.append('\\');
            } else if (next == 'n') {
                out.append('\n');
            } else if (next == 'r') {
                out.append('\r');
            } else {
                return null;
            }
        }
        return out.toString();
    }
}
