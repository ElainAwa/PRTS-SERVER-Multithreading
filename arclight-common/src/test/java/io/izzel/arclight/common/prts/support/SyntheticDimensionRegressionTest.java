/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticDimensionRegressionTest {

    @Test
    void aSharedIdIsRefusedAndAReloadStaysLoaded() {
        Object level = new Object();
        assertNull(PrtsLevelIdentity.sole("dim:a", 2, level));
        assertNull(PrtsLevelIdentity.sole("", 1, level));

        PrtsLevelLifecycle lifecycle = new PrtsLevelLifecycle();
        lifecycle.retain(lifecycle.load("dim:a"));
        lifecycle.retain(lifecycle.load("dim:a"));
        assertEquals(PrtsLevelLifecycle.Mark.RELOADED, lifecycle.current().mark);
        assertTrue(lifecycle.current().loadedIds.contains("dim:a"));
        lifecycle.retain(lifecycle.unload("dim:missing"));
        assertEquals(PrtsLevelLifecycle.Refusal.NOT_LOADED, lifecycle.current().refusal);
        assertTrue(lifecycle.current().loadedIds.contains("dim:a"));
        lifecycle.retain(lifecycle.unload("dim:a"));
        assertFalse(lifecycle.current().loadedIds.contains("dim:a"));
    }

    @Test
    void untouchedStrictReadsStayStrict() throws Exception {
        assertStillStrict("io/izzel/arclight/common/mixin/core/world/entity/EntityMixin.java",
            "WorldUUIDLeast",
            "saved world ids still call the strict accessor because a null world has no uid");
        assertStillStrict("io/izzel/arclight/common/mixin/core/world/entity/EntityMixin.java",
            "getLevel(Level.OVERWORLD)).bridge$getWorld()",
            "the overworld fallback is a player save path");
        assertStillStrict("io/izzel/arclight/common/mixin/bukkit/event/CraftEventFactoryMixin.java",
            "new CraftSign<",
            "opening a sign is a player interaction");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/level/WorldGenRegionMixin.java",
            "return ((WorldBridge) this.level).bridge$getWorld()",
            "the generated region keeps the strict accessor; the non-throwing one is separate");
        assertStillStrict("io/izzel/arclight/common/mixin/core/world/level/block/entity/LecternTileEntity1Mixin.java",
            "new Location(((WorldBridge) outerThis.getLevel()).bridge$getWorld()",
            "a block-entity location must not be built with a null world");
        assertStillStrict("io/izzel/arclight/common/mixin/core/world/level/block/entity/JukeboxBlockEntityMixin.java",
            "new Location(level.bridge$getWorld()",
            "a block-entity location must not be built with a null world");
        assertStillStrict("io/izzel/arclight/common/mixin/core/world/level/block/entity/ChiseledBookShelfBlockEntityMixin.java",
            "new org.bukkit.Location(((WorldBridge) level).bridge$getWorld()",
            "a block-entity location must not be built with a null world");
        assertStillStrict("io/izzel/arclight/common/mixin/core/world/entity/player/PlayerMixin.java",
            "bed = this.level().bridge$getWorld().getBlockAt",
            "a bed lookup is a player path");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/players/PlayerListMixin.java",
            "fromWorld.bridge$getWorld()",
            "player world changes stay on the strict accessor");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/network/ServerGamePacketListenerImplMixin.java",
            "new Location(this.player.level().bridge$getWorld(), d0, d2, d3, f1, f2)",
            "the location of one player ray stays on the strict accessor");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/network/ServerGamePacketListenerImplMixin.java",
            "this.player.level().bridge$getWorld().rayTrace",
            "the trace of that same player ray stays on the strict accessor");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/level/ServerPlayerMixin.java",
            "new PlayerChangedWorldEvent(this.getBukkitEntity(), oldLevel.bridge$getWorld())",
            "a player changing worlds stays on the strict accessor");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/level/ServerPlayerMixin.java",
            "dimensionTransition.newLevel().bridge$getWorld()",
            "a player dimension teleport stays on the strict accessor");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/level/ServerPlayerMixin.java",
            "new Location(world.bridge$getWorld(), x, y, z, yaw, pitch)",
            "a player teleport stays on the strict accessor");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/level/ServerPlayerMixin.java",
            "newWorld.bridge$getWorld()",
            "a player spawn point stays on the strict accessor");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/commands/TeleportCommandMixin.java",
            "((ServerLevelBridge) level).bridge$getWorld()",
            "teleport commands are a player path");
        assertStillStrict("io/izzel/arclight/common/mixin/core/server/commands/TimeCommandMixin.java",
            "new TimeSkipEvent(((ServerLevelBridge) serverWorld).bridge$getWorld()",
            "time commands are not a synthetic-dimension path");
        assertStillStrict("io/izzel/arclight/common/mixin/bukkit/CraftServerMixin.java",
            "world.bridge$getWorld().getName()",
            "removing a bukkit world by name stays strict");
        assertStillStrict("io/izzel/arclight/common/mixin/bukkit/CraftServerMixin.java",
            "CraftWorld bukkit = internal.bridge$getWorld()",
            "the server keeps its own bukkit world through the strict accessor");
        assertStillStrict("io/izzel/arclight/common/bridge/core/world/inventory/ContainerLevelAccessBridge.java",
            "level == null ? null : ((WorldBridge) level).bridge$getWorld()",
            "a missing level already returns before the strict read");
    }

    private static void assertStillStrict(String relative, String snippet, String reason) throws Exception {
        Path path = Path.of("src/main/java", relative);
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java", relative);
        }
        assertTrue(Files.exists(path), path.toString());
        String text = Files.readString(path);
        assertTrue(text.contains(snippet), reason + " (" + relative + ")");
    }
}
