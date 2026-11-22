/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** The owner-sync listener declaration table: one operator file names listener classes; an unknown
 * name, a broken file or a missing file yields the empty table instead of guessing. The table only
 * classifies chains; it never dispatches a listener. */
public final class OwnerSyncListenerTable {

    public static final String DIRECTORY = "prts-config";
    public static final String FILE_NAME = "event-owner-sync.yml";
    public static final String LIST_KEY = "owner-sync-listeners";

    private List<Class<?>> declared;
    private EventAdmission admission;
    private String problem;

    private OwnerSyncListenerTable(List<Class<?>> declared, String problem) {
        this.declared = List.copyOf(declared);
        this.problem = problem == null ? "" : problem;
        EventAdmission table = EventAdmission.ownerSync();
        for (Class<?> type : declared) {
            table.declare(type, EventAdmission.Mode.OWNER_SYNC);
        }
        this.admission = table;
    }

    /** The default file next to kernel.yml; a caller may load it or an explicit path. */
    public static Path defaultPath() {
        return Paths.get(DIRECTORY, FILE_NAME);
    }

    /** An empty table: every non-empty chain is undeclared and stays legacy. */
    public static OwnerSyncListenerTable empty() {
        return new OwnerSyncListenerTable(List.of(), "");
    }

    /** Loads the operator file; unknown names, parse failures and missing/empty files yield empty. */
    public static OwnerSyncListenerTable load(Path path) {
        List<String> names = names(path);
        if (names == null) {
            return new OwnerSyncListenerTable(List.of(), "missing-or-unreadable");
        }
        if (names.isEmpty()) {
            return new OwnerSyncListenerTable(List.of(), "empty-list");
        }
        List<Class<?>> types = new ArrayList<>();
        for (String name : names) {
            try {
                types.add(Class.forName(name, false, classLoader()));
            } catch (ClassNotFoundException | LinkageError | SecurityException failure) {
                return new OwnerSyncListenerTable(List.of(), "unknown-class:" + name);
            }
        }
        return new OwnerSyncListenerTable(types, "");
    }

    /** The admission table over the declared classes; empty when the declaration is empty. */
    public EventAdmission admission() {
        return admission;
    }

    /** Whether this listener, its interface or its superclass was declared OWNER_SYNC. */
    public boolean declared(Class<?> listenerType) {
        if (listenerType == null) {
            return false;
        }
        for (Class<?> type : declared) {
            if (type.isAssignableFrom(listenerType)) {
                return true;
            }
        }
        return false;
    }

    /** Re-reads the file in place, replacing the declaration only when the new file is whole. */
    public void reload(Path path) {
        OwnerSyncListenerTable replacement = load(path);
        declared = replacement.declared;
        admission = replacement.admission;
        problem = replacement.problem;
    }

    /** The non-throwing observation of the last load: empty means a whole declaration. */
    public String problem() {
        return problem;
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
