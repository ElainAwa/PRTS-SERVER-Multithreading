/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The owner aware face of the mailbox seam serves every watcher that asked for it: a second reader
 * of the same rows takes nothing away from the first, and each of them sees every call. */
class PrtsPipelineRowsOwnerTapTest {

    private static final class Recorder implements PrtsPipelineRows.MailboxOwnerTap {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void ownerTask(String world, String mailbox) {
            calls.add("task:" + world + "/" + mailbox);
        }

        @Override
        public void ownerRound(String world, String mailbox) {
            calls.add("round:" + world + "/" + mailbox);
        }
    }

    @AfterEach
    void clear() {
        PrtsPipelineRows.installOwnerTap(null);
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        assertFalse(PrtsPipelineRows.ownerTapInstalled());
    }

    @Test
    void everyWatcherSeesEveryCallOfTheFace() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        PrtsPipelineRows.installOwnerTap(first);
        PrtsPipelineRows.installOwnerTap(second);

        PrtsPipelineRows.ownerRound(new Object(), "worldgen");
        PrtsPipelineRows.ownerTask(new Object(), "light");

        assertEquals(List.of("round:-/worldgen", "task:-/light"), first.calls);
        assertEquals(first.calls, second.calls, "the second reader sees what the first saw");
    }

    @Test
    void removingOneWatcherLeavesTheOthersInstalled() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        PrtsPipelineRows.installOwnerTap(first);
        PrtsPipelineRows.installOwnerTap(second);

        PrtsPipelineRows.removeOwnerTap(first);
        assertTrue(PrtsPipelineRows.ownerTapInstalled());
        PrtsPipelineRows.ownerRound(new Object(), "worldgen");

        assertTrue(first.calls.isEmpty(), "a removed watcher is not called");
        assertEquals(List.of("round:-/worldgen"), second.calls);

        PrtsPipelineRows.removeOwnerTap(second);
        assertFalse(PrtsPipelineRows.ownerTapInstalled());
    }

    @Test
    void theSameWatcherIsInstalledOnce() {
        Recorder recorder = new Recorder();
        PrtsPipelineRows.installOwnerTap(recorder);
        PrtsPipelineRows.installOwnerTap(recorder);

        PrtsPipelineRows.ownerRound(new Object(), "worldgen");

        assertEquals(1, recorder.calls.size(), "installing twice must not double a call");
    }
}
