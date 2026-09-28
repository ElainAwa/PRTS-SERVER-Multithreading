package io.izzel.arclight.common.mod.inventory;

import net.minecraft.world.Container;
import org.bukkit.entity.HumanEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tracks the Bukkit viewers of a container.
 *
 * <p>All state of this class is one static table shared by every level and every container. The
 * wrapper synchronizes the map, but the per-container lists it hands out are plain lists, so a
 * reader outside the server thread can observe a list while it is appended to. The owner is
 * therefore the server thread: it opens and closes the containers, and it is the only writer.
 * A tick that runs worlds in parallel has to decide whether this table becomes state of the level
 * or of the container; until that decision the single-writer assumption stands.</p>
 */
public class SideViewingTracker {

    private static final Map<Container, List<HumanEntity>> VIEWERS = Collections.synchronizedMap(new WeakHashMap<>());

    public static void onOpen(Container container, HumanEntity humanEntity) {
        VIEWERS.computeIfAbsent(container, k -> new ArrayList<>()).add(humanEntity);
    }

    public static void onClose(Container container, HumanEntity humanEntity) {
        VIEWERS.computeIfAbsent(container, k -> new ArrayList<>()).remove(humanEntity);
    }

    public static List<HumanEntity> getViewers(Container container) {
        return VIEWERS.computeIfAbsent(container, k -> new ArrayList<>());
    }
}
