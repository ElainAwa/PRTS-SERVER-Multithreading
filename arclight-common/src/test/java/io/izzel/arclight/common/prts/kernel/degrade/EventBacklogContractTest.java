/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The contract the internal event backlog adds to the degradation layer. The test resolves the
 * entry by name so the same test states what is missing on a tree that has no bounded backlog. */
class EventBacklogContractTest {

    private static final String BACKLOG =
        "io.izzel.arclight.common.prts.kernel.degrade.EventBacklog";

    private static Class<?> backlogClass() throws ClassNotFoundException {
        return Class.forName(BACKLOG);
    }

    @Test
    void theBoundedEventBacklogContractExists() throws Exception {
        Class<?> backlog = backlogClass();
        assertNotNull(backlog.getDeclaredMethod("bindWorld", String.class, long.class));
        assertNotNull(backlog.getDeclaredMethod("enqueue", String.class, long.class, String.class,
            long.class));
        assertNotNull(backlog.getDeclaredMethod("forgetWorld", String.class));
        assertNotNull(backlog.getDeclaredMethod("reset"));
        assertNotNull(backlog.getDeclaredMethod("drain", int.class));
        assertNotNull(backlog.getDeclaredMethod("peek", int.class));
        assertNotNull(backlog.getDeclaredMethod("snapshot"));
        Class<?> rejection = Class.forName(BACKLOG + "$Rejection");
        assertNotNull(rejection.getField("FULL"));
        Class<?> snapshot = Class.forName(BACKLOG + "$Snapshot");
        assertNotNull(snapshot.getMethod("b4Raised"));
    }

    @Test
    void theBacklogNamesTheExistingEventQueueRung() throws Exception {
        Class<?> backlog = backlogClass();
        Method rung = backlog.getDeclaredMethod("b4Rung");
        Method code = backlog.getDeclaredMethod("b4Code");

        assertEquals(DegradeLevel.B4, rung.invoke(null));
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, code.invoke(null));
        DegradeLadder ladder = new DegradeLadder(() -> false);
        assertEquals(rung.invoke(null), ladder.rung(DegradeLevel.B4).level());
        assertEquals(code.invoke(null), ladder.rung(DegradeLevel.B4).code());
        assertTrue(ladder.rung(DegradeLevel.B4).action().contains("queue the events"));
    }
}
