/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.bukkit.Chunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PaperChunkLoadsTest {

    @Test
    void anAlreadyLoadedChunkIsReturnedWithoutThePendingFuture() {
        Chunk loaded = chunk();
        CompletableFuture<ChunkResult<ChunkAccess>> pending = new CompletableFuture<>();
        CompletableFuture<Chunk> result = PaperChunkLoads.withoutGenerating(loaded, pending);
        assertSame(loaded, result.join());
        assertFalse(pending.isDone());
        assertFalse(PaperChunkLoads.START_GENERATION);
    }

    @Test
    void aMissingChunkStaysMissingWhenGenerationIsNotStarted() {
        assertNull(PaperChunkLoads.withoutGenerating(null, null).join());
        CompletableFuture<Chunk> failed = PaperChunkLoads.withoutGenerating(null,
            CompletableFuture.completedFuture(ChunkResult.error("absent")));
        assertNull(failed.join());
    }

    private static Chunk chunk() {
        return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[] {Chunk.class},
            (proxy, method, args) -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                    return false;
                }
                if (type == int.class) {
                    return 0;
                }
                if (type == long.class) {
                    return 0L;
                }
                return null;
            });
    }
}
