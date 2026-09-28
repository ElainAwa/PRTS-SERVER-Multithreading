/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single entry point for the PRTS configuration layout.
 *
 * <pre>
 * &lt;server root&gt;/prts-config/
 *   fixes.yml                  correctness fixes
 *   modsupport.yml             mod interoperability
 *   performance.yml            performance (never carries kernel-seam settings)
 *   optional/servercore.yml    optional journal layer, disabled by default
 *   kernel.yml                 placeholder for the new kernel, disabled by default
 * </pre>
 *
 * <p>Every category owns exactly one file, named after the category, so a category can be turned
 * off — or handed to the kernel — in one place. A file is generated on first start and an existing
 * file is never overwritten, which keeps operator edits authoritative; the start then brings an
 * existing file up to the layout of this build without touching a value: a key this build declares
 * and the file does not mention is appended with its default, a leading comment block written by an
 * older build is replaced, and the layout version is written back. A file that already carries this
 * layout is left alone, so a start does not touch it. {@code /prts reload} re-reads the directory
 * without restarting the process.</p>
 *
 * <p>Parsing is deliberately dependency-free: the files are written by this class and carry only
 * {@code version}, {@code enabled} and {@code features}, so no YAML library has to be present at
 * the very early point where the mixin categories are resolved.</p>
 *
 * <p>A file that is missing, unreadable, unwritable, or carries an {@code enabled} value this class
 * does not recognize never fails the start: the category falls back to its built-in default and the
 * reason is recorded, so {@link #problems()} can show why an operator edit had no effect. A file
 * that could not be brought up to this layout is used exactly as it is on disk.</p>
 */
public final class PrtsConfigManager {

    /** Category of correctness fixes and shared PRTS infrastructure. */
    public static final String FIXES = "fixes";
    /** Category of interoperability work for mods on the registered mod list. */
    public static final String MODSUPPORT = "modsupport";
    /** Category of performance work that does not land on a new-kernel seam. */
    public static final String PERFORMANCE = "performance";
    /** Optional ServerCore layer; disabled by default. */
    public static final String OPTIONAL_SERVERCORE = "optional-servercore";
    /** Placeholder category reserved for the new kernel; disabled by default. */
    public static final String KERNEL = "kernel";

    /**
     * Version of the generated layout, header comments included. A file that declares an older
     * version is brought up to this one; a file that declares a newer one is left alone and
     * reported, because only the build that wrote it knows the keys it declared.
     */
    private static final String VERSION = "2";

    /** Value of {@code enabled} that defers to the built-in default of the category. */
    private static final String AUTO = "auto";

    private static final String HEADER =
        "# PRTS configuration. Generated on first start; an existing file is never overwritten.\n"
            + "# On start, a key this build declares and this file does not have is appended below\n"
            + "# its group with the default of this build, and a comment block written by an older\n"
            + "# build is replaced. Values set here are never changed.\n"
            + "# Reload with /prts reload (no restart).\n"
            + "# Settings that belong to a kernel seam (scheduling, tick loop, chunk pipeline,\n"
            + "# entity queries, lighting, networking, world lifecycle, storage) belong in kernel.yml\n"
            + "# only.\n";

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private static final Map<String, Boolean> ENABLED = new ConcurrentHashMap<>();

    private static final Map<String, Map<String, Boolean>> FEATURES = new ConcurrentHashMap<>();

    private static final Map<String, String> PROBLEMS = new ConcurrentHashMap<>();

    private static final Map<String, String> UPGRADES = new ConcurrentHashMap<>();

    private static volatile boolean loaded;

    /**
     * Describes one category file.
     *
     * @param file            path relative to {@code prts-config}
     * @param defaultEnabled  value used when the file is missing or unreadable
     * @param features        per-feature switches of the category and their built-in defaults
     * @param comment         human-readable purpose, written into the generated file
     */
    public record Entry(String file, boolean defaultEnabled, Map<String, Boolean> features, String comment) {
    }

    static {
        ENTRIES.put(FIXES, new Entry("fixes.yml", true, Map.of(),
            "# Correctness fixes (crashes, injection anchors, serialization fallbacks)."));
        ENTRIES.put(MODSUPPORT, new Entry("modsupport.yml", true, modSupportFeatures(),
            "# Mod interoperability. 'auto' means: apply only when the matching mod is present.\n"
                + "# 'disable-bukkit-reload-command: true' removes /reload and /bukkit:reload. A whole\n"
                + "# server reload re-enters plugin loading inside a live hybrid server and can leave\n"
                + "# plugins and mods in a state neither expects, so both commands stay available by\n"
                + "# default and a restart of the process is the supported way to reload."));
        ENTRIES.put(PERFORMANCE, new Entry("performance.yml", true, Map.of(),
            "# Performance work that does not land on a new-kernel seam."));
        ENTRIES.put(OPTIONAL_SERVERCORE, new Entry("optional/servercore.yml", false, Map.of(),
            "# Optional ServerCore layer: opt-in, mutually exclusive with an external ServerCore.\n"
                + "# Reserved for the reliable chunk-save journal only. Chunk pipeline, entity tracking\n"
                + "# and networking stay with the kernel and are never configured here."));
        ENTRIES.put(KERNEL, new Entry("kernel.yml", false, Map.of(),
            "# Reserved for the new kernel."));
    }

    /**
     * Declares the per-feature switches of the mod interoperability category.
     *
     * <p>A feature is an independent behaviour of the category. Declaring it here gives the
     * generated file one commented default per behaviour and keeps operators from having to guess
     * key names; a value that is not declared is reported as a problem and ignored.</p>
     *
     * @return the feature defaults, in the order they are written into the file
     */
    private static Map<String, Boolean> modSupportFeatures() {
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("preload-bungee-chat-classes", true);
        features.put("disable-bukkit-reload-command", false);
        return features;
    }

    private PrtsConfigManager() {
    }

    /**
     * Returns the configuration directory, resolved against the server working directory.
     *
     * @return path of {@code prts-config}
     */
    public static Path directory() {
        return Paths.get("prts-config");
    }

    /**
     * Returns the category files keyed by category name.
     *
     * @return an immutable view of the declared categories
     */
    public static Map<String, Entry> entries() {
        return Map.copyOf(ENTRIES);
    }

    /**
     * Generates the missing files, brings the existing ones up to this layout, and loads the
     * directory. Never throws: a working directory this process cannot write leaves the built-in
     * defaults in place.
     */
    public static synchronized void ensureAndLoad() {
        Map<String, String> failures = ensure();
        read();
        // read() starts a new problem list, so a file that could not be upgraded is reported after
        // it: the values on disk still apply, and only the reason they are behind is added.
        failures.forEach(PrtsConfigManager::problem);
        loaded = true;
    }

    /**
     * Generates the files this build does not find and brings an existing file up to this layout.
     *
     * <p>One file that cannot be read or written does not keep the other categories from being
     * prepared, and nothing here throws out to the caller.</p>
     *
     * @return the failures, keyed by category, for the caller to report once the read is done
     */
    private static Map<String, String> ensure() {
        Map<String, String> failures = new LinkedHashMap<>();
        UPGRADES.clear();
        try {
            Files.createDirectories(directory());
        } catch (Throwable failure) {
            for (String category : ENTRIES.keySet()) {
                failures.put(category, "directory cannot be created (" + failure
                    + "); the built-in default applies");
            }
            return failures;
        }
        for (Map.Entry<String, Entry> entry : ENTRIES.entrySet()) {
            String category = entry.getKey();
            Path file = directory().resolve(entry.getValue().file());
            try {
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, defaults(entry.getValue()), StandardCharsets.UTF_8);
                    continue;
                }
                String current = Files.readString(file, StandardCharsets.UTF_8);
                Upgraded upgraded = upgrade(entry.getValue(), current);
                if (upgraded.detail() == null) {
                    continue;
                }
                Files.writeString(file, upgraded.content(), StandardCharsets.UTF_8);
                UPGRADES.put(category, file + ": " + upgraded.detail());
            } catch (Throwable failure) {
                failures.put(category, "could not be brought up to the layout of this build ("
                    + failure + "); the file is used as it is");
            }
        }
        return failures;
    }

    /** Re-reads {@code prts-config/**} without restarting the process. */
    public static synchronized void reload() {
        read();
    }

    /**
     * Reports whether the configuration directory has been read at least once.
     *
     * @return {@code true} after {@link #ensureAndLoad()} has run
     */
    public static boolean loaded() {
        return loaded;
    }

    /**
     * Resolves the switch of a category.
     *
     * @param category one of the category constants of this class
     * @return {@code true} when the category is enabled
     */
    public static boolean isEnabled(String category) {
        Boolean value = ENABLED.get(category);
        if (value != null) {
            return value;
        }
        Entry entry = ENTRIES.get(category);
        return entry == null || entry.defaultEnabled();
    }

    /**
     * Returns the currently resolved switches.
     *
     * @return an immutable, category-ordered snapshot
     */
    public static Map<String, Boolean> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(ENABLED));
    }

    /**
     * Returns the problems found by the last read, keyed by category.
     *
     * <p>A problem is a report and not a failure: the affected category keeps its built-in default,
     * which is how a broken file becomes visible instead of silently effective.</p>
     *
     * @return an immutable, category-ordered view; empty when the last read was clean
     */
    public static Map<String, String> problems() {
        return Collections.unmodifiableMap(new TreeMap<>(PROBLEMS));
    }

    /**
     * Returns the files the last {@link #ensureAndLoad()} brought up to the layout of this build.
     *
     * <p>An upgrade changes a file an operator owns, so it is named once instead of happening
     * quietly: the appended keys take effect with the default of this build and the comment block
     * of an older build is gone, both of which are worth a line at start and in
     * {@code /prts status}.</p>
     *
     * @return an immutable, category-ordered view; empty when every file already carried this layout
     */
    public static Map<String, String> upgrades() {
        return Collections.unmodifiableMap(new TreeMap<>(UPGRADES));
    }

    /**
     * Resolves one per-feature switch of a category.
     *
     * <p>The resolution never throws and never guesses: a feature the file does not mention, or
     * mentions with a value this class does not recognize, keeps the declared built-in default, so
     * an operator edit can only turn behaviour on or off deliberately.</p>
     *
     * @param category one of the category constants of this class
     * @param name     feature name as declared by the category
     * @param fallback value used when the last read did not provide the feature
     * @return {@code true} when the feature is enabled
     */
    public static boolean feature(String category, String name, boolean fallback) {
        Map<String, Boolean> features = FEATURES.get(category);
        Boolean value = features == null ? null : features.get(name);
        return value == null ? fallback : value;
    }

    /**
     * Returns the per-feature switches of one category as they were last read.
     *
     * @param category one of the category constants of this class
     * @return an immutable view; empty when the category declares no feature
     */
    public static Map<String, Boolean> features(String category) {
        Map<String, Boolean> features = FEATURES.get(category);
        return features == null ? Map.of() : features;
    }

    /**
     * Renders the default content of one category file.
     *
     * @param entry the category to render
     * @return the generated file content, including its header comments
     */
    public static String defaults(Entry entry) {
        StringBuilder builder = new StringBuilder(HEADER)
            .append(entry.comment()).append('\n')
            .append("version: ").append(VERSION).append('\n')
            .append("enabled: ").append(entry.defaultEnabled()).append('\n');
        if (entry.features().isEmpty()) {
            return builder.append("features: {}\n").toString();
        }
        builder.append("features:").append('\n');
        entry.features().forEach((name, value) ->
            builder.append("  ").append(name).append(": ").append(value).append('\n'));
        return builder.toString();
    }

    /**
     * Result of bringing one file up to the layout of this build.
     *
     * @param content the content to write; the content of the file when nothing changes
     * @param detail  what changed, or {@code null} when the file already carries this layout
     */
    record Upgraded(String content, String detail) {
    }

    /** One insertion into a file: {@code lines} added at {@code index}. */
    private record Edit(int index, List<String> lines) {
    }

    /**
     * Brings one file up to the layout of this build without touching a value the operator set.
     *
     * <p>Three changes are possible. A key this build declares and the file does not mention is
     * appended to the end of its group, carrying the default of this build and the version that
     * added it. A leading comment block that is not the block of this build is replaced, which is
     * how the text of an older build leaves a file. The layout version is written back. Every other
     * line, and in particular every value, is carried over unchanged, and a file that already
     * carries this layout comes back as the same string, which is what keeps a start from touching
     * it.</p>
     *
     * <p>A file that declares a version newer than this build is not changed at all: only the build
     * that wrote it knows the keys it declared, and taking the version back would hide that.</p>
     *
     * <p>Visible to the tests of this package, which drive a file content through it directly.</p>
     *
     * @param entry   the category the file belongs to
     * @param content the file as it is on disk
     * @return the content to write and what changed; the input and a {@code null} detail when the
     *         file already carries this layout
     */
    static Upgraded upgrade(Entry entry, String content) {
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        Integer declared = topLevelKeys(lines, 0).get("version");
        if (declared != null && isNewerVersion(value(lines.get(declared)))) {
            return new Upgraded(content, null);
        }

        String versionChange = null;
        int added = 0;
        boolean headerRefreshed = false;

        List<String> expected = headerLines(entry);
        int headerEnd = 0;
        while (headerEnd < lines.size() && isComment(lines.get(headerEnd))) {
            headerEnd++;
        }
        if (!inOrder(lines.subList(0, headerEnd), expected)) {
            lines.subList(0, headerEnd).clear();
            lines.addAll(0, expected);
            headerRefreshed = true;
        }

        Map<String, Integer> keys = topLevelKeys(lines, headerRefreshed ? expected.size() : headerEnd);
        Integer versionIndex = keys.get("version");
        if (versionIndex == null) {
            // the key is written with the other appended keys, below
        } else {
            String current = value(lines.get(versionIndex));
            if (!VERSION.equals(current)) {
                lines.set(versionIndex, "version: " + VERSION);
                versionChange = "version " + current + " -> " + VERSION;
            }
        }

        List<String> appended = new ArrayList<>();
        if (versionIndex == null) {
            appended.add("# added in v" + VERSION);
            appended.add("version: " + VERSION);
            added++;
        }
        if (keys.get("enabled") == null) {
            appended.add("# added in v" + VERSION);
            appended.add("enabled: " + entry.defaultEnabled());
            added++;
        }

        List<Edit> edits = new ArrayList<>();
        Integer featuresIndex = keys.get("features");
        if (featuresIndex == null) {
            appended.add("# added in v" + VERSION);
            if (entry.features().isEmpty()) {
                appended.add("features: {}");
                added++;
            } else {
                appended.add("features:");
                for (Map.Entry<String, Boolean> feature : entry.features().entrySet()) {
                    appended.add("  " + feature.getKey() + ": " + feature.getValue());
                    added++;
                }
            }
        } else {
            Set<String> present = featureNames(lines, featuresIndex);
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, Boolean> feature : entry.features().entrySet()) {
                if (present.contains(feature.getKey())) {
                    continue;
                }
                missing.add("  # added in v" + VERSION);
                missing.add("  " + feature.getKey() + ": " + feature.getValue());
                added++;
            }
            if (!missing.isEmpty()) {
                if (lines.get(featuresIndex).trim().endsWith("{}")) {
                    // an empty flow mapping carries no block entries, so the header becomes a block
                    lines.set(featuresIndex, "features:");
                }
                edits.add(new Edit(featuresEnd(lines, featuresIndex), missing));
            }
        }
        if (!appended.isEmpty()) {
            // without a features line to sit above, everything this build adds ends the file
            edits.add(new Edit(featuresIndex == null ? endOfFile(lines) : featuresIndex, appended));
        }

        edits.sort(Comparator.comparingInt(Edit::index).reversed());
        for (Edit edit : edits) {
            lines.addAll(edit.index(), edit.lines());
        }

        String upgraded = String.join("\n", lines);
        if (upgraded.equals(content)) {
            return new Upgraded(content, null);
        }
        List<String> changes = new ArrayList<>();
        if (versionChange != null) {
            changes.add(versionChange);
        }
        if (added > 0) {
            changes.add("added " + added + (added == 1 ? " key" : " keys"));
        }
        if (headerRefreshed) {
            changes.add("refreshed header");
        }
        return new Upgraded(upgraded, changes.isEmpty() ? "layout" : String.join(", ", changes));
    }

    /**
     * Renders the comment block this build writes above the keys of a file.
     *
     * @param entry the category the file belongs to
     * @return the header and the category comment, one element per line
     */
    private static List<String> headerLines(Entry entry) {
        List<String> lines = new ArrayList<>(
            Arrays.asList((HEADER + entry.comment() + "\n").split("\n", -1)));
        // the split keeps the empty element behind the final newline; it is not a line of the block
        lines.remove(lines.size() - 1);
        return lines;
    }

    /**
     * Finds the top-level keys of a file, ignoring the indented entries of the features block.
     *
     * @param lines the file content
     * @param from  index of the first line after the leading comment block
     * @return key name to line index, for the first line that declares it
     */
    private static Map<String, Integer> topLevelKeys(List<String> lines, int from) {
        Map<String, Integer> keys = new LinkedHashMap<>();
        for (int index = from; index < lines.size(); index++) {
            String line = lines.get(index);
            if (isIndented(line)) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                keys.putIfAbsent(trimmed.substring(0, colon).trim(), index);
            }
        }
        return keys;
    }

    /**
     * Returns the index after the last line of the features block.
     *
     * <p>A blank line inside the block does not end it, so a new entry lands behind the entries that
     * are there instead of in the middle of them.</p>
     *
     * @param lines  the file content
     * @param header index of the {@code features} line
     * @return the index a new entry of the block is appended at
     */
    private static int featuresEnd(List<String> lines, int header) {
        int end = header + 1;
        for (int index = header + 1; index < lines.size(); index++) {
            if (isIndented(lines.get(index))) {
                end = index + 1;
            } else if (!lines.get(index).isBlank()) {
                break;
            }
        }
        return end;
    }

    /**
     * Collects the feature names a file already mentions.
     *
     * @param lines  the file content
     * @param header index of the {@code features} line
     * @return the names, in file order
     */
    private static Set<String> featureNames(List<String> lines, int header) {
        Set<String> names = new LinkedHashSet<>();
        int end = featuresEnd(lines, header);
        for (int index = header + 1; index < end; index++) {
            String line = lines.get(index).trim();
            if (line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                names.add(line.substring(0, colon).trim());
            }
        }
        return names;
    }

    /**
     * Returns the index a new last line is appended at, in front of the newline that ends the file.
     *
     * @param lines the file content
     * @return the index of the trailing empty element, or the size when the file has none
     */
    private static int endOfFile(List<String> lines) {
        int last = lines.size() - 1;
        return last >= 0 && lines.get(last).isEmpty() ? last : lines.size();
    }

    private static boolean isComment(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private static boolean isIndented(String line) {
        return !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
    }

    /**
     * Reports whether a comment block already carries every line of this build, in order.
     *
     * <p>An operator may add a line of their own to the block; as long as the text of this build is
     * still in it, the block is not rewritten. A block an older build wrote does not carry those
     * lines, which is how the text of that build leaves the file.</p>
     *
     * @param block    the comment block at the top of the file
     * @param expected the block this build writes
     * @return {@code true} when the block does not have to be replaced
     */
    private static boolean inOrder(List<String> block, List<String> expected) {
        int index = 0;
        for (String line : block) {
            if (index < expected.size() && expected.get(index).equals(line)) {
                index++;
            }
        }
        return index == expected.size();
    }

    /**
     * Reads the value of a scalar line.
     *
     * @param line a line of a generated file
     * @return the value without surrounding quotes, or an empty string when the line carries none
     */
    private static String value(String line) {
        String trimmed = line.trim();
        int colon = trimmed.indexOf(':');
        return colon < 0 ? "" : unquote(trimmed.substring(colon + 1).trim());
    }

    /**
     * Reports whether a declared layout version was written by a build newer than this one.
     *
     * @param declared the value of the {@code version} key
     * @return {@code true} when the file belongs to a newer build and has to be left alone
     */
    private static boolean isNewerVersion(String declared) {
        try {
            return Integer.parseInt(declared) > Integer.parseInt(VERSION);
        } catch (NumberFormatException notANumber) {
            // a value that is not a number is not a claim about a newer build
            return false;
        }
    }

    private static void read() {
        ENABLED.clear();
        FEATURES.clear();
        PROBLEMS.clear();
        for (Map.Entry<String, Entry> entry : ENTRIES.entrySet()) {
            ENABLED.put(entry.getKey(), read(entry.getKey(), entry.getValue()));
        }
    }

    /**
     * Reads one category file and records every problem it shows.
     *
     * @param category category name, used when a problem is recorded
     * @param entry    the file to read
     * @return the switch of the file, or the built-in default when the file cannot be trusted
     */
    private static boolean read(String category, Entry entry) {
        Path file = directory().resolve(entry.file());
        if (!Files.exists(file)) {
            problem(category, "file is missing; the built-in default applies");
            return entry.defaultEnabled();
        }
        String version = null;
        String enabled = null;
        Map<String, Boolean> features = new LinkedHashMap<>();
        try {
            boolean inFeatures = false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                    if (inFeatures) {
                        readFeature(category, trimmed, features);
                    }
                    continue;
                }
                inFeatures = false;
                if (trimmed.startsWith("version:")) {
                    version = unquote(trimmed.substring("version:".length()).trim());
                } else if (trimmed.startsWith("enabled:")) {
                    enabled = unquote(trimmed.substring("enabled:".length()).trim());
                } else if (trimmed.startsWith("features:")) {
                    inFeatures = true;
                }
            }
        } catch (Throwable failure) {
            problem(category, "cannot be read (" + failure + "); the built-in default applies");
            return entry.defaultEnabled();
        }
        Map<String, Boolean> resolved = new LinkedHashMap<>();
        entry.features().forEach((name, fallback) -> resolved.put(name, features.getOrDefault(name, fallback)));
        features.keySet().forEach(name -> {
            if (!entry.features().containsKey(name)) {
                problem(category, "unknown feature '" + name + "' is ignored");
            }
        });
        FEATURES.put(category, Collections.unmodifiableMap(resolved));
        if (version != null && isNewerVersion(version)) {
            problem(category, "declares version " + version + ", this build writes version " + VERSION
                + "; it was written by a newer build and is left as it is");
        }
        if (enabled == null) {
            problem(category, "no enabled key; the built-in default applies");
            return entry.defaultEnabled();
        }
        if (AUTO.equalsIgnoreCase(enabled)) {
            return entry.defaultEnabled();
        }
        Boolean parsed = parseEnabled(enabled);
        if (parsed == null) {
            problem(category, "unrecognized enabled value '" + enabled + "'; the built-in default applies");
            return entry.defaultEnabled();
        }
        return parsed;
    }

    /**
     * Reads one indented line of the {@code features} block.
     *
     * @param category category name, used when a problem is recorded
     * @param trimmed  the line without surrounding blanks
     * @param features collected values, written into
     */
    private static void readFeature(String category, String trimmed, Map<String, Boolean> features) {
        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            problem(category, "cannot read feature line '" + trimmed + "'");
            return;
        }
        String name = trimmed.substring(0, colon).trim();
        String raw = unquote(trimmed.substring(colon + 1).trim());
        Boolean value = parseEnabled(raw);
        if (value == null) {
            problem(category, "feature " + name + ": unrecognized value '" + raw
                + "'; the built-in default applies");
            return;
        }
        features.put(name, value);
    }

    private static Boolean parseEnabled(String value) {
        if ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
            && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void problem(String category, String detail) {
        PROBLEMS.merge(category, detail, (first, second) -> first + "; " + second);
    }
}
