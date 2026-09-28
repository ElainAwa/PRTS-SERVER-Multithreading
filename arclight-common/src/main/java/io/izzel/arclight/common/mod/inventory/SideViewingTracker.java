package io.izzel.arclight.common.mod.inventory;

import net.minecraft.world.Container;
import org.bukkit.entity.HumanEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks the Bukkit viewers of a container.
 *
 * <p>All state of this class is one static table shared by every level and every container. The
 * owner is the server thread: it opens and closes the containers, and it is the only writer. The
 * table is handed out to callers as the live list of a container, so the list itself is
 * copy-on-write: a reader on another thread (an asynchronous plugin task asking an inventory for
 * its viewers, for example) sees either the state before or after an open or close, never a list
 * that is being appended to. Writes stay rare - one container is opened or closed by a player
 * action - so the copy per write costs nothing measurable, and reads take no lock.</p>
 *
 * <p>A tick that runs worlds in parallel still has to decide whether this table becomes state of
 * the level or of the container; until that decision the single-writer assumption stands.</p>
 */
public class SideViewingTracker {

    private static final Map<Container, List<HumanEntity>> VIEWERS = Collections.synchronizedMap(new WeakHashMap<>());

    public static void onOpen(Container container, HumanEntity humanEntity) {
        VIEWERS.computeIfAbsent(container, k -> new CopyOnWriteArrayList<>()).add(humanEntity);
    }

    public static void onClose(Container container, HumanEntity humanEntity) {
        VIEWERS.computeIfAbsent(container, k -> new CopyOnWriteArrayList<>()).remove(humanEntity);
    }

    public static List<HumanEntity> getViewers(Container container) {
        return VIEWERS.computeIfAbsent(container, k -> new CopyOnWriteArrayList<>());
    }
}
