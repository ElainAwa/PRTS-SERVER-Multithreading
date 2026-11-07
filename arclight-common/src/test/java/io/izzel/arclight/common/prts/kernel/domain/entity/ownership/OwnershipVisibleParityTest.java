/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client-visible contract of a skipped ownership row: the host tick is not run, so the
 * commit has to carry the same position, previous position, counter and tracker impulse a host
 * tick would have left. The test keeps the serial and candidate sides as plain visible state, so
 * it needs no server and no client. */
class OwnershipVisibleParityTest {

    @Test
    void twentyFourBatRowsKeepTheSameVisibleCountAndPositions() {
        TickState[] serial = new TickState[24];
        TickState[] candidate = new TickState[24];
        for (int i = 0; i < 24; i++) {
            serial[i] = row(i, 4.0 + (i % 8) * 1.2, 14.0 + (i / 8) * 1.0);
            candidate[i] = new TickState();
            candidate[i].copyFrom(serial[i]);
        }
        assertEquals(serial.length, candidate.length, "the candidate lost visible rows");
        for (int i = 0; i < serial.length; i++) {
            HostVisibleState.Visible visible = HostVisibleState.of(candidate[i]);
            assertEquals(serial[i].x, visible.x(), 0.0, "candidate x differs from serial");
            assertEquals(serial[i].y, visible.y(), 0.0, "candidate y differs from serial");
            assertEquals(serial[i].z, visible.z(), 0.0, "candidate z differs from serial");
            assertEquals(serial[i].xo, visible.xo(), 0.0, "candidate previous x differs");
            assertEquals(serial[i].yo, visible.yo(), 0.0, "candidate previous y differs");
            assertEquals(serial[i].zo, visible.zo(), 0.0, "candidate previous z differs");
            assertEquals(serial[i].tickCount, visible.tickCount(), "candidate counter differs");
            assertTrue(visible.impulse(), "the tracker impulse was not carried");
        }
    }

    @Test
    void theSkipCommitsTheVisibleStateAfterTheModel() throws Exception {
        String lookup = source("EntityTickOwnership.java");
        int apply = lookup.indexOf("model.apply(entity, answer);");
        int commit = lookup.indexOf("HostVisibleState.commit(entity, answer);");
        assertTrue(apply >= 0, "the model apply is missing");
        assertTrue(commit > apply, "the visible state is not committed after the model");
        assertTrue(lookup.contains("model.commitHostSteps(entity, answer);"));
    }

    @Test
    void theHostVisibleCommitWritesEveryFieldTheTrackerReads() throws Exception {
        String host = source("HostVisibleState.java");
        for (String required : new String[]{"entity.setPos(", "entity.xo =", "entity.yo =",
            "entity.zo =", "entity.tickCount =", "entity.hasImpulse = true"}) {
            assertTrue(host.contains(required), "missing host-visible write: " + required);
        }
        assertFalse(host.contains("Thread.sleep"), "a tick-thread commit must not block");
    }

    private static TickState row(int index, double x, double z) {
        TickState state = new TickState();
        state.entityId = 100 + index;
        state.tickCount = 900 + index;
        state.x = x;
        state.y = 70.0;
        state.z = z;
        state.xo = x - 0.05;
        state.yo = 70.0;
        state.zo = z;
        state.vx = 0.05;
        state.vy = 0.0;
        state.vz = 0.0;
        state.appliedScale = 1.0F;
        state.headTurnLimit = 75;
        state.onGround = true;
        return state;
    }

    private static String source(String name) throws Exception {
        Path relative = Path.of("src/main/java/io/izzel/arclight/common/prts/kernel/domain/entity/ownership", name);
        Path fallback = Path.of("arclight-common").resolve(relative);
        Path path = Files.exists(relative) ? relative : fallback;
        return Files.readString(path);
    }
}
