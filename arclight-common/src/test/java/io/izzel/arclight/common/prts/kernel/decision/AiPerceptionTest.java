/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The pure half of the first AI slice: the worker sees only the frozen player positions. */
class AiPerceptionTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @AfterEach
    void tearDown() {
        AiPerception.reset();
    }

    @Test
    void picksNearestInsideRange() {
        AiPerception.Request request = request(0.0, 64.0, 0.0, 100.0,
            List.of(new AiPerception.PlayerPos(A, 9.0, 64.0, 0.0),
                new AiPerception.PlayerPos(B, 3.0, 64.0, 0.0),
                new AiPerception.PlayerPos(C, 50.0, 64.0, 0.0)));
        AiPerception.Result result = AiPerception.nearest(request);
        assertEquals(B, result.target());
        assertEquals(9.0, result.distanceSq(), 0.0);
    }

    @Test
    void ignoresPlayersOutsideFrozenRange() {
        AiPerception.Request request = request(0.0, 64.0, 0.0, 4.0,
            List.of(new AiPerception.PlayerPos(A, 10.0, 64.0, 0.0),
                new AiPerception.PlayerPos(B, -3.0, 64.0, 0.0)));
        AiPerception.Result result = AiPerception.nearest(request);
        assertEquals(B, result.target());
        assertEquals(9.0, result.distanceSq(), 0.0);
    }

    @Test
    void noCandidateKeepsHostPath() {
        AiPerception.Request request = request(0.0, 64.0, 0.0, 2.0,
            List.of(new AiPerception.PlayerPos(A, 3.0, 64.0, 0.0)));
        AiPerception.Result result = AiPerception.nearest(request);
        assertNull(result.target());
        assertEquals(Double.NaN, result.distanceSq());
    }

    @Test
    void emptyFrozenListIsNoDecision() {
        AiPerception.Request request = request(0.0, 64.0, 0.0, 16.0, List.of());
        assertNull(AiPerception.nearest(request).target());
    }

    private static AiPerception.Request request(double x, double y, double z, double range,
                                                List<AiPerception.PlayerPos> players) {
        return new AiPerception.Request(A, "minecraft:overworld", 100L, x, y, z,
            range * range, players, 1L);
    }
}
