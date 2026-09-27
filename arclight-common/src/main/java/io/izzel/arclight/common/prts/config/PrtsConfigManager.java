/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * off — or handed to the kernel — in one place. Files are generated on first start only and an
 * existing file is never overwritten, which keeps operator edits authoritative.
 * {@code /prts reload} re-reads the directory without restarting the process.</p>
 *
 * <p>Parsing is deliberately dependency-free: the files are written by this class and carry only
 * {@code version}, {@code enabled} and {@code features}, so no YAML library has to be present at
 * the very early point where the mixin categories are resolved.</p>
 *
 * <p>A file that is missing, unreadable, or carries an {@code enabled} value this class does not
 * recognize never fails the start: the category falls back to its built-in default and the reason
 * is recorded, so {@link #problems()} can show why an operator edit had no effect.</p>
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

    /** Version of the generated file format; any other version is reported as a problem. */
    private static final String VERSION = "1";

    /** Value of {@code enabled} that defers to the built-in default of the category. */
    private static final String AUTO = "auto";

    private static final String HEADER =
        "# PRTS configuration. Generated on first start; an existing file is never overwritten.\n"
            + "# Reload with /prts reload (no restart).\n"
            + "# Settings that belong to a kernel seam (scheduling, tick loop, chunk pipeline,\n"
            + "# entity queries, lighting, networking, world lifecycle, storage) belong in kernel.yml\n"
            + "# only.\n";

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private static final Map<String, Boolean> ENABLED = new ConcurrentHashMap<>();

    private static final Map<String, String> PROBLEMS = new ConcurrentHashMap<>();

    private static volatile boolean loaded;

    /**
     * Describes one category file.
     *
     * @param file            path relative to {@code prts-config}
     * @param defaultEnabled  value used when the file is missing or unreadable
     * @param comment         human-readable purpose, written into the generated file
     */
    public record Entry(String file, boolean defaultEnabled, String comment) {
    }

    static {
        ENTRIES.put(FIXES, new Entry("fixes.yml", true,
            "# Correctness fixes (crashes, injection anchors, serialization fallbacks)."));
        ENTRIES.put(MODSUPPORT, new Entry("modsupport.yml", true,
            "# Mod interoperability. 'auto' means: apply only when the matching mod is present."));
        ENTRIES.put(PERFORMANCE, new Entry("performance.yml", true,
            "# Performance work that does not land on a new-kernel seam."));
        ENTRIES.put(OPTIONAL_SERVERCORE, new Entry("optional/servercore.yml", false,
            "# Optional ServerCore layer: opt-in, mutually exclusive with an external ServerCore.\n"
                + "# Reserved for the reliable chunk-save journal only. Chunk pipeline, entity tracking\n"
                + "# and networking stay with the kernel and are never configured here."));
        ENTRIES.put(KERNEL, new Entry("kernel.yml", false,
            "# Reserved for the new kernel."));
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
     * Generates the missing files and loads the directory. Never throws: an unwritable working
     * directory leaves the built-in defaults in place.
     */
    public static synchronized void ensureAndLoad() {
        try {
            Files.createDirectories(directory());
            for (Map.Entry<String, Entry> entry : ENTRIES.entrySet()) {
                Path file = directory().resolve(entry.getValue().file());
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, defaults(entry.getValue()), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
            // defaults still apply, and an unwritable directory must not stop the server
        }
        read();
        loaded = true;
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
     * Renders the default content of one category file.
     *
     * @param entry the category to render
     * @return the generated file content, including its header comments
     */
    public static String defaults(Entry entry) {
        return HEADER
            + entry.comment() + "\n"
            + "version: " + VERSION + "\n"
            + "enabled: " + entry.defaultEnabled() + "\n"
            + "features: {}\n";
    }

    private static void read() {
        ENABLED.clear();
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
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("version:")) {
                    version = unquote(trimmed.substring("version:".length()).trim());
                } else if (trimmed.startsWith("enabled:")) {
                    enabled = unquote(trimmed.substring("enabled:".length()).trim());
                }
            }
        } catch (Throwable failure) {
            problem(category, "cannot be read (" + failure + "); the built-in default applies");
            return entry.defaultEnabled();
        }
        if (version != null && !VERSION.equals(version)) {
            problem(category, "declares version " + version + ", this build reads version " + VERSION);
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
