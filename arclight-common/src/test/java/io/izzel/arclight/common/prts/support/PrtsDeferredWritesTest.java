/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** What a deferred write keeps while it waits for its commit: the position is frozen; the abandoned
 * platform-data copy path no longer exists because the real Level write is the only host entry. */
class PrtsDeferredWritesTest {

    @Test
    void aLevelWriteFreezesThePositionItWasHanded() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        PrtsDeferredWrites.LevelWrite write = new PrtsDeferredWrites.LevelWrite(null, mutable, null, 3, 512);

        mutable.set(9, 9, 9);

        assertEquals(new BlockPos(1, 2, 3), write.position());
        assertNotSame(mutable, write.position());
    }
}
