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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The PRTS configuration layout: one file per category under {@code prts-config/}, generated on
 * first start and brought up to this build's layout without touching an operator value. Never
 * fails the start - a broken file keeps the built-in default and is reported through {@link #problems()}.
 */
public final class PrtsConfigManager {

    public static final String FIXES = "fixes";
    public static final String MODSUPPORT = "modsupport";
    public static final String PERFORMANCE = "performance";
    public static final String OPTIONAL_SERVERCORE = "optional-servercore";
    public static final String KERNEL = "kernel";

    private static final String VERSION = "2";

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

    private static final Map<String, Map<String, Integer>> NUMBERS = new ConcurrentHashMap<>();

    private static final Map<String, String> PROBLEMS = new ConcurrentHashMap<>();

    private static final Map<String, String> UPGRADES = new ConcurrentHashMap<>();

    private static final CopyOnWriteArrayList<Runnable> RELOAD_LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean loaded;

    /** One declared category file: its path, default switch, switches and numbers, and its purpose text. */
    public record Entry(String file, boolean defaultEnabled, Map<String, Boolean> features,
                        Map<String, IntSetting> numbers, String comment) {

        public Entry(String file, boolean defaultEnabled, Map<String, Boolean> features, String comment) {
            this(file, defaultEnabled, features, Map.of(), comment);
        }
    }

    /**
     * One whole-number setting: the default written into a generated file, and the range an operator
     * edit is clamped to (an out-of-range value is clamped and reported, never applied as written).
     */
    public record IntSetting(int defaultValue, int min, int max) {

        public IntSetting {
            if (min > max) {
                throw new IllegalArgumentException("empty range [" + min + ", " + max + "]");
            }
            if (defaultValue < min || defaultValue > max) {
                throw new IllegalArgumentException("default " + defaultValue + " outside [" + min + ", " + max + "]");
            }
        }
    }

    static {
        ENTRIES.put(FIXES, new Entry("fixes.yml", true, Map.of(),
            "# Correctness fixes (crashes, injection anchors, serialization fallbacks)."));
        ENTRIES.put(MODSUPPORT, new Entry("modsupport.yml", true, modSupportFeatures(),
            modSupportNumbers(),
            "# Mod interoperability. 'auto' means: apply only when the matching mod is present.\n"
                + "# Every switch here is read at the moment its patch runs, so /prts reload applies a\n"
                + "# change without a restart. A switch that repairs a fault defaults to true; a switch\n"
                + "# that only changes how much work a mod does defaults to false and is turned on\n"
                + "# deliberately.\n"
                + "# 'guard-create-funnel-pickup: true' skips a funnel pickup whose filtering behaviour\n"
                + "# cannot be read instead of letting the mod throw out of the block tick. It is off by\n"
                + "# default, because no reading justifies changing funnel behaviour without the observed\n"
                + "# fault.\n"
                + "# 'guard-create-tree-cutter-bounds: true' answers air for a tree search that reaches a\n"
                + "# chunk which is not loaded, so a saw cannot pull a ring of chunks into memory.\n"
                + "# 'guard-sable-voxel-cache: true' takes the memoized block lookup of the physics mod\n"
                + "# under one lock, which keeps two threads from corrupting the same cache table.\n"
                + "# 'serialize-sable-native-calls: true' runs the physics step and the rope calls of that\n"
                + "# mod under one lock, so its native library is never entered twice at the same time.\n"
                + "# 'narrow-unlockable-recipes-login-sync: true' sends the recipe refresh of the recipe\n"
                + "# mod to the player who logged in instead of to every player online.\n"
                + "# 'guard-minecolonies-compat-discovery: true' keeps one failing compatibility scan\n"
                + "# from ending the remaining scan steps of the colony mod.\n"
                + "# 'shorten-sbw-projectile-life: true' answers a shorter default lifetime for a\n"
                + "# projectile of the vehicle mod.\n"
                + "# 'spread-sbw-motion-sync: true' answers a wider motion sync interval for its fast\n"
                + "# projectiles.\n"
                + "# 'narrow-sbw-particle-viewers: true' sends a particle of that mod only to players\n"
                + "# within 96 blocks instead of to every player of the level.\n"
                + "# 'throttle-sbw-iff-payloads: true' sends the friend-or-foe update every third tick,\n"
                + "# and only while the mod setting still holds the value the mod ships.\n"
                + "# 'narrow-sbw-vehicle-shoot: true' sends the shoot broadcast of that mod to the players\n"
                + "# tracking the vehicle instead of to the whole server.\n"
                + "# 'resync-tacz-gun-state-on-respawn: true' re-sends the gun state of a player whose\n"
                + "# respawn packet was pushed out, which a skin refresh does without the client\n"
                + "# rebuilding that state.\n"
                + "# 'disable-bukkit-reload-command: true' removes /reload and /bukkit:reload. A whole\n"
                + "# server reload re-enters plugin loading inside a live hybrid server and can leave\n"
                + "# plugins and mods in a state neither expects, so both commands stay available by\n"
                + "# default and a restart of the process is the supported way to reload.\n"
                + "# 'tree-cutter-node-budget' and 'tree-cutter-time-budget-ms' bound one tree search of\n"
                + "# the machinery mod (block lookups, wall clock). Zero means the unbounded search of\n"
                + "# the mod; a search that runs out of budget is cut short and the saw continues with\n"
                + "# the next one."));
        ENTRIES.put(PERFORMANCE, new Entry("performance.yml", true, Map.of(),
            "# Performance work that does not land on a new-kernel seam."));
        ENTRIES.put(OPTIONAL_SERVERCORE, new Entry("optional/servercore.yml", false, journalFeatures(),
            journalNumbers(),
            "# Optional ServerCore layer: opt-in, mutually exclusive with an external ServerCore.\n"
                + "# Reserved for the reliable chunk-save journal only. Chunk pipeline, entity tracking\n"
                + "# and networking stay with the kernel and are never configured here.\n"
                + "# 'reliable-chunk-save: true' writes each flush cycle to a journal file before the\n"
                + "# region files are touched, and replays it after an unclean exit; it needs the\n"
                + "# category itself to be enabled as well.\n"
                + "# 'journal-interval-seconds' is how often a cycle starts (at least 5 seconds),\n"
                + "# 'journal-chunks-per-tick' caps how many chunks one tick serializes."));
        ENTRIES.put(KERNEL, new Entry("kernel.yml", false, kernelFeatures(), kernelNumbers(),
            "# The new kernel scaffolding: the write decision point, the per-class self timer, the\n"
                + "# time-budget share table and the wait point registry. The metering pieces observe;\n"
                + "# the intent channel can apply deferred writes on the server thread when both routing\n"
                + "# and commit are enabled. The category is off, so a server that does not opt in\n"
                + "# pays only the lightweight reload hook.\n"
                + "# 'write-path-guard: true' watches the write paths this build already hooks: the\n"
                + "# server thread writing its own world takes a short path that allocates nothing,\n"
                + "# and every other writer is counted by thread and holder. It records by default;\n"
                + "# enforcement or routing changes the disposition explicitly.\n"
                + "# 'enforce-unregistered-writes: false' is the switch that would refuse instead of\n"
                + "# record: off, an unregistered write remains on its original path; on, the same write\n"
                + "# is refused with a code and a count.\n"
                + "# 'commit-intents: false' leaves the intent channel alone: what a routed write\n"
                + "# froze into it stays there, and the depth in the readout says how many wait. On,\n"
                + "# the commit segment applies each intent it reaches, on the thread that drives the\n"
                + "# tick, and the channel drains in the order it froze.\n"
                + "# 'route-unregistered-writes: false' leaves an unregistered write on its original\n"
                + "# path after recording the decision. On, the write is handed to the intent channel\n"
                + "# instead of being written immediately. The two switches answer different questions\n"
                + "# - which writes are deferred, and when a deferred write lands.\n"
                + "# 'self-timers', 'share-table' and 'wait-registry' turn the three metering pieces on\n"
                + "# once the category is on; every row they publish is also published as zero.\n"
                + "# 'self-window-seconds' is the metering window (at least ten minutes) and\n"
                + "# 'self-warmup-seconds' is the leading part published as warm-up.\n"
                + "# 'e-budget-ms' is what one tick may spend, 'world-share-ms' is the fair share of\n"
                + "# one world inside it, 'reserve-ms' is the single column only a forced\n"
                + "# materialization or a migration wait may draw from, and 'host-overhead-ms' is the\n"
                + "# part the class rows may not borrow.\n"
                + "# 'intent-queue-cap' is the depth at which one world's intent shard refuses instead\n"
                + "# of growing and 'commit-budget' is how many intents one tick's commit walk may\n"
                + "# reach, so a large queue cannot turn one tick into a long synchronous drain.\n"
                + "# 'wait-bound-ms' is the upper bound of one wait, and 'retry-budget' is how many\n"
                + "# retries one failing intent carries before its refusal is final and the channel\n"
                + "# releases it.\n"
                + "# 'refuse-unregistered-waits: false' leaves a wait at a call site no row covers on\n"
                + "# its original path and only counts it. On, the registry answers that wait with a\n"
                + "# refusal code as well; the count is kept and the call site still runs the host\n"
                + "# path unchanged, because the refusal is an answer and not an interception.\n"
                + "# 'dispatch-parallel: false' leaves the first parallel domain switched off: no plan,\n"
                + "# no worker thread and no intermediate slot exists while it is off. On, one tick\n"
                + "# freezes its entity work into tasks, runs each task on a named worker and merges\n"
                + "# the results on the tick thread at the next tick boundary.\n"
                + "# 'worker-count: 0' derives the pool size from the machine (one to four workers,\n"
                + "# never more than the processors minus one); a declared value is clamped to one\n"
                + "# through eight. 'worker-queue-cap' is how many batches may be in flight before the\n"
                + "# tick thread takes the work over, 'worker-batch-chunks' is how many chunks one\n"
                + "# region covers, 'worker-deadline-grace-ms' is how long the merge waits past the\n"
                + "# tick boundary (zero cancels immediately), and 'worker-retry-budget' is how many\n"
                + "# retryable faults a worker attempt carries before it falls back; it is never\n"
                + "# larger than 'retry-budget' above.\n"
                + "# 'dispatch-identical-only: false' is the takeover boundary of the write-back leg -\n"
                + "# off, the leg lands every value the domain computed, so the open tier owns the\n"
                + "# kinematics it froze; on, it may only take over a row whose position, orientation\n"
                + "# and velocity are bit-identical to what the world already holds (the host path's\n"
                + "# own result for that tick) - an agreeing row is counted and left untouched, a\n"
                + "# differing row stays with the host path. The open tier changes no host semantics\n"
                + "# only in that mode.\n"
                + "# 'dispatch-takeover: false' keeps the domain compute-only: the merge settles a batch\n"
                + "# by reading the world back in the same tick and counting which rows the host path\n"
                + "# itself produced, and it lands nothing, so every position, orientation and velocity\n"
                + "# stays with the host. On, the leg hands the batch to the intent channel and the\n"
                + "# commit segment lands it - the tier the controlled three-probe comparison registered\n"
                + "# as a semantic deviation (registration M4-OPEN), reachable only by this opt-in.\n"
                + "# 'tick-plan: false' leaves the planning period switched off: no plan is frozen and\n"
                + "# nothing consumes an order. On, the tick freezes one plan - the job graph, its\n"
                + "# topological order, the commit order derived from it, the share table and the mode\n"
                + "# of every world and domain pair - and publishes it for the next tick.\n"
                + "# 'write-version-slots: false' leaves the version of a write right domain unkept.\n"
                + "# The planning period grants nothing when it declares a job, and a write carries no\n"
                + "# version. On, a declared domain keeps a version when the plan is frozen, a world that\n"
                + "# leaves the live set retires and reclaims its slots, and a write that names the wrong\n"
                + "# version is refused with a code and the site, thread, world and tick it happened on.\n"
                + "# 'write-owner-grants: false' leaves the write right a job declares unheld. On, the\n"
                + "# planning period turns the right a frozen job asks for into the one credential a writer\n"
                + "# may hold, for the hold window the declaration named, and releases it when the job is\n"
                + "# gone from the plan; a demand it cannot grant is refused with a code and the site,\n"
                + "# thread, world and tick it was refused on.\n"
                + "# 'job-graph: false' leaves the job layer switched off, so a domain dispatches in the\n"
                + "# order it always did. On, the declared jobs are frozen into a graph, the scheduler\n"
                + "# hands them out along that order and along their affinity, the declared bound of\n"
                + "# jobs in flight is enforced instead of growing a queue, and the cost of the layer is\n"
                + "# metered into the same per-class timers the share table is planned from.\n"
                + "# 'commit-log: false' leaves every commit producer on its own path. On, the producers\n"
                + "# report what they committed to one log, which accepts a report only while the order\n"
                + "# of each world and domain pair keeps rising, keeps it in a ring per pair and folds\n"
                + "# what it read so a replayed run can be compared with the run that happened. The log\n"
                + "# records and judges; it does not intercept a write.\n"
                + "# 'job-queue-cap' is the upper bound of the declarations one tick may hand to the\n"
                + "# planning period, 'commit-ring-cap' the capacity of one world and domain ring and\n"
                + "# 'plan-history-cap' how many recent plans an order may be resolved against.\n"
                + "# 'wait-actions: false' keeps the wait ladder counting only: a rung that was\n"
                + "# reached publishes its entered count and no action runs. On, a rung may report\n"
                + "# the action it ran, and 'wait-rollback-ticks' is the run of clean ticks its return\n"
                + "# gate waits for beside the progress signal of the wait point having to move again.\n"
                + "# Both are off or declared, so a shipped server keeps its wait behaviour."));
    }

    private static Map<String, Boolean> modSupportFeatures() {
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("preload-bungee-chat-classes", true);
        features.put("disable-bukkit-reload-command", false);
        features.put("guard-create-funnel-pickup", false);
        features.put("guard-create-tree-cutter-bounds", true);
        features.put("guard-sable-voxel-cache", true);
        features.put("serialize-sable-native-calls", true);
        features.put("narrow-unlockable-recipes-login-sync", true);
        features.put("guard-minecolonies-compat-discovery", true);
        features.put("shorten-sbw-projectile-life", false);
        features.put("spread-sbw-motion-sync", false);
        features.put("narrow-sbw-particle-viewers", false);
        features.put("throttle-sbw-iff-payloads", false);
        features.put("narrow-sbw-vehicle-shoot", false);
        features.put("resync-tacz-gun-state-on-respawn", true);
        return features;
    }

    private static Map<String, IntSetting> modSupportNumbers() {
        Map<String, IntSetting> numbers = new LinkedHashMap<>();
        numbers.put("tree-cutter-node-budget", new IntSetting(0, 0, 65536));
        numbers.put("tree-cutter-time-budget-ms", new IntSetting(0, 0, 60000));
        return numbers;
    }

    private static Map<String, Boolean> kernelFeatures() {
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("enforce-unregistered-writes", false);
        features.put("self-timers", true);
        features.put("share-table", true);
        features.put("wait-registry", true);
        features.put("refuse-unregistered-waits", false);
        features.put("degrade-actions", false);
        features.put("wait-actions", false);
        features.put("write-path-guard", true);
        features.put("commit-intents", false);
        features.put("route-unregistered-writes", false);
        features.put("dispatch-parallel", false);
        features.put("dispatch-identical-only", false);
        features.put("dispatch-takeover", false);
        features.put("tick-plan", false);
        features.put("write-version-slots", false);
        features.put("write-owner-grants", false);
        features.put("job-graph", false);
        features.put("commit-log", false);
        features.put("safety-net", false);
        features.put("safety-degrade", false);
        features.put("dual-exits", false);
        features.put("plan-feedback", false);
        return features;
    }

    private static Map<String, IntSetting> kernelNumbers() {
        Map<String, IntSetting> numbers = new LinkedHashMap<>();
        numbers.put("self-window-seconds", new IntSetting(600, 600, 86400));
        numbers.put("self-warmup-seconds", new IntSetting(60, 0, 3600));
        numbers.put("e-budget-ms", new IntSetting(50, 1, 1000));
        numbers.put("world-share-ms", new IntSetting(8, 1, 1000));
        numbers.put("reserve-ms", new IntSetting(4, 0, 500));
        numbers.put("host-overhead-ms", new IntSetting(2, 0, 500));
        numbers.put("intent-queue-cap", new IntSetting(256, 1, 65536));
        numbers.put("commit-budget", new IntSetting(64, 1, 4096));
        numbers.put("wait-bound-ms", new IntSetting(50, 1, 60000));
        numbers.put("degrade-rollback-ticks", new IntSetting(3, 1, 600));
        numbers.put("wait-rollback-ticks", new IntSetting(3, 1, 600));
        numbers.put("retry-budget", new IntSetting(2, 0, 16));
        numbers.put("worker-count", new IntSetting(0, 0, 8));
        numbers.put("worker-queue-cap", new IntSetting(32, 1, 256));
        numbers.put("worker-batch-chunks", new IntSetting(4, 1, 64));
        numbers.put("worker-deadline-grace-ms", new IntSetting(0, 0, 1000));
        numbers.put("worker-retry-budget", new IntSetting(1, 0, 2));
        numbers.put("job-queue-cap", new IntSetting(256, 1, 4096));
        numbers.put("commit-ring-cap", new IntSetting(512, 1, 65536));
        numbers.put("plan-history-cap", new IntSetting(8, 1, 64));
        numbers.put("safety-zero-effect-ticks", new IntSetting(100, 1, 6000));
        numbers.put("safety-cascade-cap", new IntSetting(3, 1, 64));
        numbers.put("exit-window-ticks", new IntSetting(20, 1, 6000));
        return numbers;
    }

    private static Map<String, Boolean> journalFeatures() {
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("reliable-chunk-save", false);
        return features;
    }

    private static Map<String, IntSetting> journalNumbers() {
        Map<String, IntSetting> numbers = new LinkedHashMap<>();
        numbers.put("journal-interval-seconds", new IntSetting(30, 5, 3600));
        numbers.put("journal-chunks-per-tick", new IntSetting(50, 1, 4096));
        return numbers;
    }

    private PrtsConfigManager() {
    }

    /** @return the configuration directory, resolved against the server working directory */
    public static Path directory() {
        return Paths.get("prts-config");
    }

    /** @return the declared category files, keyed by category name */
    public static Map<String, Entry> entries() {
        return Map.copyOf(ENTRIES);
    }

    /**
     * Generates the missing files, brings the existing ones up to this layout and loads the directory;
     * never throws.
     */
    public static synchronized void ensureAndLoad() {
        Map<String, String> failures = ensure();
        read();
        // read() starts a new problem list, so an upgrade failure is reported after it: the values on disk still apply.
        failures.forEach(PrtsConfigManager::problem);
        loaded = true;
    }

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
        for (Runnable listener : RELOAD_LISTENERS) {
            listener.run();
        }
    }

    /** Registers a callback invoked after a successful configuration reload. */
    public static void addReloadListener(Runnable listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        RELOAD_LISTENERS.addIfAbsent(listener);
    }

    /** @return true after {@link #ensureAndLoad()} has run */
    public static boolean loaded() {
        return loaded;
    }

    /** @return true when the category is enabled, from its file and its built-in default */
    public static boolean isEnabled(String category) {
        Boolean value = ENABLED.get(category);
        if (value != null) {
            return value;
        }
        Entry entry = ENTRIES.get(category);
        return entry == null || entry.defaultEnabled();
    }

    /** @return the currently resolved switches, in category order */
    public static Map<String, Boolean> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(ENABLED));
    }

    /**
     * @return the problems of the last read, keyed by category; a problem is a report, not a failure,
     *     so the affected category keeps its built-in default instead of silently taking effect
     */
    public static Map<String, String> problems() {
        return Collections.unmodifiableMap(new TreeMap<>(PROBLEMS));
    }

    /**
     * @return the files the last {@link #ensureAndLoad()} upgraded, in category order; an upgrade changes
     *     a file an operator owns, so it is named instead of happening quietly
     */
    public static Map<String, String> upgrades() {
        return Collections.unmodifiableMap(new TreeMap<>(UPGRADES));
    }

    /**
     * Resolves one per-feature switch; a feature the file does not mention, or mentions with an
     * unrecognized value, keeps the declared built-in default.
     * @param fallback value used when the last read did not provide the feature
     */
    public static boolean feature(String category, String name, boolean fallback) {
        Map<String, Boolean> features = FEATURES.get(category);
        Boolean value = features == null ? null : features.get(name);
        return value == null ? fallback : value;
    }

    /**
     * Resolves one per-feature switch against its declaration, so a caller cannot drift from the
     * generated file.
     * @throws IllegalArgumentException when the category does not declare the name
     */
    public static boolean feature(String category, String name) {
        Map<String, Boolean> features = FEATURES.get(category);
        Boolean value = features == null ? null : features.get(name);
        if (value != null) {
            return value;
        }
        Entry entry = ENTRIES.get(category);
        Boolean declared = entry == null ? null : entry.features().get(name);
        if (declared == null) {
            throw new IllegalArgumentException("category " + category + " declares no feature " + name);
        }
        return declared;
    }

    /** @return the per-feature switches of one category as last read; empty when it declares none */
    public static Map<String, Boolean> features(String category) {
        Map<String, Boolean> features = FEATURES.get(category);
        return features == null ? Map.of() : features;
    }

    /**
     * Resolves one whole-number setting against its declaration; an operator edit outside the declared
     * range was already clamped and reported by the last read.
     * @throws IllegalArgumentException when the category does not declare the name
     */
    public static int number(String category, String name) {
        Map<String, Integer> numbers = NUMBERS.get(category);
        Integer value = numbers == null ? null : numbers.get(name);
        if (value != null) {
            return value;
        }
        Entry entry = ENTRIES.get(category);
        IntSetting setting = entry == null ? null : entry.numbers().get(name);
        if (setting == null) {
            throw new IllegalArgumentException("category " + category + " declares no setting " + name);
        }
        return setting.defaultValue();
    }

    /** @return the whole-number settings of one category as last read; empty when it declares none */
    public static Map<String, Integer> numbers(String category) {
        Map<String, Integer> numbers = NUMBERS.get(category);
        return numbers == null ? Map.of() : numbers;
    }

    /** @return the generated content of one category file, header comments included */
    public static String defaults(Entry entry) {
        StringBuilder builder = new StringBuilder(HEADER)
            .append(entry.comment()).append('\n')
            .append("version: ").append(VERSION).append('\n')
            .append("enabled: ").append(entry.defaultEnabled()).append('\n');
        if (entry.features().isEmpty() && entry.numbers().isEmpty()) {
            return builder.append("features: {}\n").toString();
        }
        builder.append("features:").append('\n');
        entry.features().forEach((name, value) ->
            builder.append("  ").append(name).append(": ").append(value).append('\n'));
        entry.numbers().forEach((name, setting) ->
            builder.append("  ").append(name).append(": ").append(setting.defaultValue()).append('\n'));
        return builder.toString();
    }

    /** @param detail what changed, or null when the file already carries this layout */
    record Upgraded(String content, String detail) {
    }

    private record Edit(int index, List<String> lines) {
    }

    /**
     * Brings one file up to the layout of this build without touching a value the operator set: it
     * appends the keys this build declares, replaces the comment block of an older build and writes
     * the layout version back. A file that declares a newer version is left alone.
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
        Map<String, String> defaults = declaredEntries(entry);
        Integer featuresIndex = keys.get("features");
        if (featuresIndex == null) {
            appended.add("# added in v" + VERSION);
            if (defaults.isEmpty()) {
                appended.add("features: {}");
                added++;
            } else {
                appended.add("features:");
                for (Map.Entry<String, String> feature : defaults.entrySet()) {
                    appended.add("  " + feature.getKey() + ": " + feature.getValue());
                    added++;
                }
            }
        } else {
            Set<String> present = featureNames(lines, featuresIndex);
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, String> feature : defaults.entrySet()) {
                if (present.contains(feature.getKey())) {
                    continue;
                }
                missing.add("  # added in v" + VERSION);
                missing.add("  " + feature.getKey() + ": " + feature.getValue());
                added++;
            }
            if (!missing.isEmpty()) {
                if (lines.get(featuresIndex).trim().endsWith("{}")) {
                    // An empty flow mapping carries no block entries, so the header becomes a block.
                    lines.set(featuresIndex, "features:");
                }
                edits.add(new Edit(featuresEnd(lines, featuresIndex), missing));
            }
        }
        if (!appended.isEmpty()) {
            // Without a features line to sit above, everything this build adds ends the file.
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

    private static Map<String, String> declaredEntries(Entry entry) {
        Map<String, String> declared = new LinkedHashMap<>();
        entry.features().forEach((name, value) -> declared.put(name, String.valueOf(value)));
        entry.numbers().forEach((name, setting) -> declared.put(name, String.valueOf(setting.defaultValue())));
        return declared;
    }

    private static List<String> headerLines(Entry entry) {
        List<String> lines = new ArrayList<>(
            Arrays.asList((HEADER + entry.comment() + "\n").split("\n", -1)));
        // The split keeps the empty element behind the final newline; it is not a line of the block.
        lines.remove(lines.size() - 1);
        return lines;
    }

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

    private static boolean inOrder(List<String> block, List<String> expected) {
        int index = 0;
        for (String line : block) {
            if (index < expected.size() && expected.get(index).equals(line)) {
                index++;
            }
        }
        return index == expected.size();
    }

    private static String value(String line) {
        String trimmed = line.trim();
        int colon = trimmed.indexOf(':');
        return colon < 0 ? "" : unquote(trimmed.substring(colon + 1).trim());
    }

    private static boolean isNewerVersion(String declared) {
        try {
            return Integer.parseInt(declared) > Integer.parseInt(VERSION);
        } catch (NumberFormatException notANumber) {
            // A value that is not a number is not a claim about a newer build.
            return false;
        }
    }

    private static void read() {
        ENABLED.clear();
        FEATURES.clear();
        NUMBERS.clear();
        PROBLEMS.clear();
        for (Map.Entry<String, Entry> entry : ENTRIES.entrySet()) {
            ENABLED.put(entry.getKey(), read(entry.getKey(), entry.getValue()));
        }
    }

    private static boolean read(String category, Entry entry) {
        Path file = directory().resolve(entry.file());
        if (!Files.exists(file)) {
            problem(category, "file is missing; the built-in default applies");
            return entry.defaultEnabled();
        }
        String version = null;
        String enabled = null;
        Map<String, Boolean> features = new LinkedHashMap<>();
        Map<String, Integer> numbers = new LinkedHashMap<>();
        try {
            boolean inFeatures = false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                    if (inFeatures) {
                        readFeature(category, entry, trimmed, features, numbers);
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
        Map<String, Integer> resolvedNumbers = new LinkedHashMap<>();
        entry.numbers().forEach((name, setting) -> resolvedNumbers.put(name,
            numbers.getOrDefault(name, setting.defaultValue())));
        Set<String> declared = new LinkedHashSet<>(entry.features().keySet());
        declared.addAll(entry.numbers().keySet());
        features.keySet().forEach(name -> {
            if (!declared.contains(name)) {
                problem(category, "unknown feature '" + name + "' is ignored");
            }
        });
        numbers.keySet().forEach(name -> {
            if (!declared.contains(name)) {
                problem(category, "unknown feature '" + name + "' is ignored");
            }
        });
        FEATURES.put(category, Collections.unmodifiableMap(resolved));
        NUMBERS.put(category, Collections.unmodifiableMap(resolvedNumbers));
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

    // Which kind of value a line carries is decided by the declaration, not by its text: a declared
    private static void readFeature(String category, Entry entry, String trimmed,
                                    Map<String, Boolean> features, Map<String, Integer> numbers) {
        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            problem(category, "cannot read feature line '" + trimmed + "'");
            return;
        }
        String name = trimmed.substring(0, colon).trim();
        String raw = unquote(trimmed.substring(colon + 1).trim());
        IntSetting setting = entry.numbers().get(name);
        if (setting != null) {
            readNumber(category, name, raw, setting, numbers);
            return;
        }
        Boolean value = parseEnabled(raw);
        if (value == null) {
            problem(category, "feature " + name + ": unrecognized value '" + raw
                + "'; the built-in default applies");
            return;
        }
        features.put(name, value);
    }

    private static void readNumber(String category, String name, String raw, IntSetting setting,
                                   Map<String, Integer> numbers) {
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            problem(category, "setting " + name + ": '" + raw + "' is not a whole number; the default "
                + setting.defaultValue() + " applies");
            return;
        }
        if (value < setting.min() || value > setting.max()) {
            int clamped = Math.max(setting.min(), Math.min(setting.max(), value));
            problem(category, "setting " + name + ": " + value + " is outside [" + setting.min() + ", "
                + setting.max() + "]; " + clamped + " applies");
            value = clamped;
        }
        numbers.put(name, value);
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
