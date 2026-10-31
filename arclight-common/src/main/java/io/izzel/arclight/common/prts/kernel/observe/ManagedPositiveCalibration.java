/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Test-only positive calibration for the managed read window. It is disabled by default and never
 * changes the normal chunk path: only when the property is enabled and the caller is the owner
 * thread does it issue one real chunk load under an explicit managed context. */
public final class ManagedPositiveCalibration {

    public static final String PROPERTY = "arclight.prts.managedPositiveCalibration";

    public enum Result {
        DISABLED,
        NO_WORLD,
        NOT_OWNER,
        NO_LOAD,
        POSITIVE
    }

    @FunctionalInterface
    interface Loader {
        boolean load(ServerLevel level, BlockPos pos);
    }

    private ManagedPositiveCalibration() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Production entry: disabled unless the property and owner-thread checks pass. */
    public static Result calibrate(ServerLevel level, BlockPos pos) {
        boolean hasWorld = level != null && pos != null;
        boolean owner = hasWorld && level.getServer() != null && level.getServer().isSameThread();
        return calibrate(enabled(), owner, hasWorld, level, pos, (world, position) -> {
            world.getChunk(position.getX() >> 4, position.getZ() >> 4);
            return true;
        });
    }

    static Result calibrate(boolean propertyOn, boolean owner, boolean hasWorld, ServerLevel level,
                            BlockPos pos, Loader loader) {
        if (!propertyOn) {
            return Result.DISABLED;
        }
        if (!hasWorld) {
            return Result.NO_WORLD;
        }
        if (!owner) {
            return Result.NOT_OWNER;
        }
        long chunkPos = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        String worldId = level == null ? "" : level.dimension().location().toString();
        MaterializedUnloadedReadings.beginWindow(1);
        long readId = MaterializedUnloadedReadings.begin(worldId, chunkPos, "calibration");
        boolean loaded;
        try {
            loaded = loader.load(level, pos);
        } catch (Throwable thrown) {
            MaterializedUnloadedReadings.end(readId,
                MaterializedUnloadedReadings.Outcome.SOURCE_UNAVAILABLE);
            return Result.NO_LOAD;
        }
        MaterializedUnloadedReadings.end(readId, loaded
            ? MaterializedUnloadedReadings.Outcome.COMPLETE
            : MaterializedUnloadedReadings.Outcome.REJECTED);
        return loaded ? Result.POSITIVE : Result.NO_LOAD;
    }
}
