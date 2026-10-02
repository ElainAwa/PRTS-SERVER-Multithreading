/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import net.minecraft.core.BlockPos;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What a deferred write holds on to while it waits for its commit.
 *
 * <p>The caller keeps its own objects. A position handed over on a mutable instance can be moved by
 * the caller afterwards, and a platform write can hand over data it keeps editing; the write that the
 * commit segment eventually performs must be the one that was frozen, not whatever the caller moved
 * on to. So the deferred write takes a snapshot of the coordinate and keeps a copy of the data.</p>
 */
class PrtsDeferredWritesTest {

    @Test
    void aLevelWriteFreezesThePositionItWasHanded() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        PrtsDeferredWrites.LevelWrite write = new PrtsDeferredWrites.LevelWrite(null, mutable, null, 3, 512);

        mutable.set(9, 9, 9);

        assertEquals(new BlockPos(1, 2, 3), write.position());
        assertNotSame(mutable, write.position());
    }

    @Test
    void aPlatformWriteKeepsACopyOfTheDataItWasHanded() {
        BlockData copy = stub("copy");
        AtomicInteger clones = new AtomicInteger();
        BlockData original = (BlockData) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {BlockData.class}, (proxy, method, args) -> {
                if ("clone".equals(method.getName())) {
                    clones.incrementAndGet();
                    return copy;
                }
                return stubAnswer(proxy, method, args, "original");
            });

        BlockData returned = PrtsDeferredWrites.PlatformWrite.copyOf(original);

        assertEquals(1, clones.get(), "the data is copied when the write is handed over");
        assertSame(copy, returned, "the write keeps the copy and not the caller's object");
        assertNotSame(original, returned);
    }

    private static BlockData stub(String name) {
        return (BlockData) Proxy.newProxyInstance(PrtsDeferredWritesTest.class.getClassLoader(),
            new Class<?>[] {BlockData.class}, (proxy, method, args) -> {
                if ("clone".equals(method.getName())) {
                    return proxy;
                }
                return stubAnswer(proxy, method, args, name);
            });
    }

    private static Object stubAnswer(Object proxy, Method method, Object[] args, String name) {
        return switch (method.getName()) {
            case "toString" -> name;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
}
