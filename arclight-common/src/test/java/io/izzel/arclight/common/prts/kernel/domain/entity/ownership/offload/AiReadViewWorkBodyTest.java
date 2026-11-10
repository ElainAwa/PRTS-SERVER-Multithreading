/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiReadViewWorkBodyTest {

    private static AiReadView view() {
        return new AiReadView(Map.of("e1", "state"), Map.of("b1", 1), Map.of("p1", "home"),
            new AiReadView.Version(7L, 11L));
    }

    @Test
    void unknownReadAndWriteFailExplicitly() {
        AiReadView view = view();
        assertEquals("state", view.entity("e1"));
        assertThrows(AiReadView.UnknownRead.class, () -> view.entity("missing"));
        assertThrows(AiReadView.WriteRejected.class, () -> view.set("e1", "x"));
        assertEquals(7L, view.version().worldEpoch());
    }

    @Test
    void workBodyRunsEntryOnReadViewOnce() throws Exception {
        AtomicLong calls = new AtomicLong();
        AiWorkBodyPrototype body = new AiWorkBodyPrototype(view(), v -> {
            calls.incrementAndGet();
            assertEquals("home", v.poi("p1"));
            return 1L;
        });
        assertEquals(1L, body.run(null, null, new CancelToken(3L)));
        assertEquals(1L, calls.get());
    }
}
