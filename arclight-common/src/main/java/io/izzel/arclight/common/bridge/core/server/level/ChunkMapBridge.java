package io.izzel.arclight.common.bridge.core.server.level;

import io.izzel.arclight.common.mod.util.ArclightCallbackExecutor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.function.BooleanSupplier;

public interface ChunkMapBridge {

    void bridge$tick(BooleanSupplier hasMoreTime);

    ArclightCallbackExecutor bridge$getCallbackExecutor();

    /**
     * Returns the chunk holders this map is currently keeping, without creating or loading one.
     *
     * <p>Readers that must not disturb the pipeline (a journal taking stock of unsaved chunks, for
     * example) need the loaded set as it is. The list is only valid on the server thread, and it is
     * a live view: do not iterate it while the map is being modified from the same thread.</p>
     *
     * @return the chunk holders this map keeps, in map order
     */
    Iterable<ChunkHolder> bridge$getLoadedChunksIterable();

    ChunkHolder bridge$chunkHolderAt(long chunkPos);

    void bridge$setViewDistance(int i);

    void bridge$setChunkGenerator(ChunkGenerator generator);
}
