/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import java.util.List;

/** Stable internal payload contract: row kinds, generation/version rule, unknown refusal, and the
 * lifecycle of one snapshot (capture -> arena -> worker read; stale/unloaded rows are refused). */
final class AiPayloadContract {

    static void validate(AiArenaPayload.Snapshot snapshot, long expectedGeneration) {
        if (snapshot == null) throw new IllegalArgumentException("payload is required");
        if (snapshot.generation() != expectedGeneration) {
            throw new IllegalStateException("stale AI payload generation");
        }
        for (AiArenaPayload.Row row : snapshot.rows()) {
            if (row.kind() == null) throw new IllegalArgumentException("unknown AI row kind");
            if (row.version() != expectedGeneration) {
                throw new IllegalStateException("stale AI row version");
            }
        }
    }

    static List<AiArenaPayload.Kind> kinds() {
        return List.of(AiArenaPayload.Kind.values());
    }

    private AiPayloadContract() {
    }
}
