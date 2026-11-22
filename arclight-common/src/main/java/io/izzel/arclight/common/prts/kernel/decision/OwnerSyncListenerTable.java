/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** The owner-sync listener declaration table: one operator file names listener classes. The whole
 * table is one immutable snapshot behind a volatile reference, so a reload publishes either the
 * complete new declaration or leaves the old one readable; readers never lock the owner path. */
public final class OwnerSyncListenerTable {

    public static final String DIRECTORY = "prts-config";
    public static final String FILE_NAME = "event-owner-sync.yml";
    public static final String LIST_KEY = "owner-sync-listeners";

    private enum Kind {
        DECLARATION,
        EXPLICIT_EMPTY,
        INVALID
    }

    private record Snapshot(List<Class<?>> declared, EventAdmission admission, String problem,
                            Kind kind) {
    }

    private volatile Snapshot snapshot;

    private OwnerSyncListenerTable(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** The default file next to kernel.yml; a caller may load it or an explicit path. */
    public static Path defaultPath() {
        return Paths.get(DIRECTORY, FILE_NAME);
    }

    /** An empty table: every non-empty chain is undeclared and stays legacy. */
    public static OwnerSyncListenerTable empty() {
        return new OwnerSyncListenerTable(new Snapshot(List.of(),
            EventAdmission.ownerSync(), "", Kind.EXPLICIT_EMPTY));
    }

    /** Loads the operator file. A missing or broken file is an empty table on the first load;
     * {@link #reload(Path)} treats that same result differently and keeps the previous declaration. */
    public static OwnerSyncListenerTable load(Path path) {
        return new OwnerSyncListenerTable(read(path));
    }

    /** The admission table from the current immutable snapshot. */
    public EventAdmission admission() {
        return snapshot.admission();
    }

    /** Whether this listener, its interface or its superclass was declared OWNER_SYNC. */
    public boolean declared(Class<?> listenerType) {
        if (listenerType == null) {
            return false;
        }
        for (Class<?> type : snapshot.declared()) {
            if (type.isAssignableFrom(listenerType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-reads the file. A missing, unreadable or unknown-class declaration is refused and the old
     * declaration stays readable (only the problem observation updates); an explicit empty list is a
     * valid empty declaration and replaces the old one. The whole snapshot is published once.
     */
    public void reload(Path path) {
        Snapshot current = snapshot;
        Snapshot replacement = read(path);
        if (replacement.kind() == Kind.INVALID) {
            snapshot = new Snapshot(current.declared(), current.admission(),
                replacement.problem(), current.kind());
            return;
        }
        snapshot = replacement;
    }

    /** The non-throwing observation of the last load: empty means a whole declaration. */
    public String problem() {
        return snapshot.problem();
    }

    private static Snapshot read(Path path) {
        List<String> names = names(path);
        if (names == null) {
            return new Snapshot(List.of(), EventAdmission.ownerSync(),
                "missing-or-unreadable", Kind.INVALID);
        }
        if (names.isEmpty()) {
            return new Snapshot(List.of(), EventAdmission.ownerSync(), "empty-list",
                Kind.EXPLICIT_EMPTY);
        }
        List<Class<?>> types = new ArrayList<>();
        for (String name : names) {
            try {
                types.add(Class.forName(name, false, classLoader()));
            } catch (ClassNotFoundException | LinkageError | SecurityException failure) {
                return new Snapshot(List.of(), EventAdmission.ownerSync(),
                    "unknown-class:" + name, Kind.INVALID);
            }
        }
        EventAdmission admission = EventAdmission.ownerSync();
        for (Class<?> type : types) {
            admission.declare(type, EventAdmission.Mode.OWNER_SYNC);
        }
        return new Snapshot(List.copyOf(types), admission, "", Kind.DECLARATION);
    }

    private static List<String> names(Path path) {
        try {
            File file = path.toFile();
            if (!file.isFile()) {
                return null;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            List<String> names = yaml.getStringList(LIST_KEY);
            List<String> cleaned = new ArrayList<>();
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    cleaned.add(name.trim());
                }
            }
            return cleaned;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? OwnerSyncListenerTable.class.getClassLoader() : loader;
    }
}
