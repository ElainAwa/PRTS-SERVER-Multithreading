/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W8 capture-driven offline model/worker copy; no server and no world read. */
class CaptureDrivenMobAiModelTest {

    private static final Path CAPTURE = Path.of("/home/elysia/project/team/基座核查/P1/P1-B01/准备-1轮/P1-B03-W8-capture/capture.jsonl");

    private static Map<String, String> fields(JsonObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : o.keySet()) m.put(k, o.get(k).getAsString());
        return m;
    }

    private static List<Map<String, String>> rows() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        if (!Files.exists(CAPTURE)) return rows;
        for (String line : Files.readAllLines(CAPTURE)) {
            if (!line.isBlank()) rows.add(fields(JsonParser.parseString(line).getAsJsonObject()));
        }
        return rows;
    }

    @Test
    void planToHostDeltaPredictionOverCapture() throws Exception {
        List<Map<String, String>> rows = rows();
        Assumptions.assumeTrue(!rows.isEmpty(), "W8 capture evidence is absent");
        MobAiTickModel model = new MobAiTickModel();
        int pairs = 0;
        for (Map<String, String> plan : rows) {
            if (!"plan".equals(plan.get("kind"))) continue;
            Map<String, String> host = null;
            for (Map<String, String> candidate : rows) {
                if ("host".equals(candidate.get("kind"))
                    && plan.get("uuid").equals(candidate.get("uuid"))
                    && String.valueOf(Long.parseLong(plan.get("tick")) + 1).equals(candidate.get("tick"))) {
                    host = candidate;
                    break;
                }
            }
            if (host == null) continue;
            TickState state = new TickState();
            model.captureCaptureRow(new MobAiTickModel.CaptureRow(plan), state);
            assertTrue(model.compute(state));
            @SuppressWarnings("unchecked")
            Map<String, String> predicted = (Map<String, String>) state.modelAnswer;
            for (String deterministic : List.of("game_time", "ai", "activities")) {
                assertEquals(host.get(deterministic), predicted.get(deterministic),
                    "deterministic field " + deterministic + " differs for tick " + plan.get("tick"));
            }
            assertEquals(host.get("tick"), predicted.get("tick"), "tick delta");
            pairs++;
            if (pairs >= 40) break;
        }
        assertTrue(pairs > 0, "no capture pair available");
    }

    @Test
    void captureRowDrivesLeaseComputeAndApplyOnce() throws Exception {
        List<Map<String, String>> rows = rows();
        Assumptions.assumeTrue(!rows.isEmpty(), "W8 capture evidence is absent");
        Map<String, String> plan = rows.stream().filter(r -> "plan".equals(r.get("kind"))).findFirst().orElseThrow();
        MobAiTickModel model = new MobAiTickModel();
        TickState state = new TickState();
        model.captureCaptureRow(new MobAiTickModel.CaptureRow(plan), state);
        OwnershipLease lease = new OwnershipLease(1L, 1, 1L);
        OwnershipLease.EntityCapability cap = new OwnershipLease.EntityCapability(1, 2L, 3L, 4,
            true, true, (byte) 0, 0.0, 0.0, 0.0, 5L, 0);
        lease.issue(cap, model, state);
        lease.publish(Runnable::run, 1);
        assertEquals(OwnershipLease.SETTLED, lease.state(0));
        assertEquals(1L, model.computeRows());
        model.apply(null, lease.answer(0));
        model.noteApplied();
        assertEquals(1L, model.appliedCount());
        lease.consume(0);
    }
}
