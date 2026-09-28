package io.izzel.arclight.common.mod.util.remapper.patcher.integrated;

import io.izzel.arclight.api.PluginPatcher;
import io.izzel.arclight.common.prts.support.PrtsLuckPermsPatcher;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/*
 * This can be useful, so look at what I've found!
 */
public class IntegratedPatcher implements PluginPatcher {

    private static final Map<String, BiConsumer<ClassNode, ClassRepo>> SPECIFIC = new HashMap<>() {};
    private static final List<BiConsumer<ClassNode, ClassRepo>> GENERAL = new ArrayList<>();

    static {
        // Handle WorldEdit reflective using NMS
        // Their naming mapping is behind the version, syncing manually
        SPECIFIC.put("com/sk89q/worldedit/bukkit/adapter/impl/v1_21/StaticRefraction", WorldEdit::handleStaticRefraction);
        SPECIFIC.put("com/sk89q/worldedit/bukkit/adapter/impl/v1_21/PaperweightAdapter", WorldEdit::handleBukkitAdapter);
        // The table is private and offers no registration hook, so a plugin that needs its own
        // rewrite can only be listed here. The list is part of the cache key below, which is what
        // makes an added entry invalidate plugin classes that were cached before it existed.
        SPECIFIC.put("me/lucko/luckperms/bukkit/inject/permissible/PermissibleInjector",
            PrtsLuckPermsPatcher::handlePermissibleInjector);
    }

    @Override
    public String version() {
        String implVersion = this.getClass().getPackage().getImplementationVersion();
        StringBuilder sb = new StringBuilder();
        if (implVersion != null) {
            sb.append("version=").append(implVersion);
        }
        sb.append(" patchers=[");
        sb.append("WorldEdit 1.21.1 4, LuckPerms PermissibleInjector");
        sb.append("]");
        return sb.toString();
    }

    @Override
    public void handleClass(ClassNode node, ClassRepo classRepo) {
        BiConsumer<ClassNode, ClassRepo> consumer = SPECIFIC.get(node.name);
        if (consumer != null) {
            consumer.accept(node, classRepo);
        } else {
            for (BiConsumer<ClassNode, ClassRepo> general : GENERAL) {
                general.accept(node, classRepo);
            }
        }
    }
}
