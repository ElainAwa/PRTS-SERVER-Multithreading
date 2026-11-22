/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/** Which entity update segments may run on an owner worker once a segment program exists: only the
 * ones with no chunk or POI demand. This is a declaration, not a driver; the owner batch still runs
 * the whole tick body, so it must not be applied to that path. */
final class EntityUpdateSegmentPolicy {

    enum Segment {
        MOB_TICK_TAIL,
        LIVING_ENTITY_TICK_TAIL,
        LIVING_ENTITY_AI_STEP_TAIL,
        MOB_AI_STEP_LOOTING,
        SERVER_AI_STEP_PREFIX,
        NAVIGATION_TICK,
        SERVER_AI_STEP_SUCCESSOR,
        BRAIN_TICK
    }

    private static final Set<Segment> WORKER_SEGMENTS = Set.of(
        Segment.MOB_TICK_TAIL,
        Segment.SERVER_AI_STEP_PREFIX,
        Segment.LIVING_ENTITY_TICK_TAIL);

    /** Segments whose suffix must stay on the server thread, so the phase never batches the row. */
    private static final Set<Segment> HOST_ONLY_SEGMENTS = Set.of(
        Segment.LIVING_ENTITY_TICK_TAIL);

    private EntityUpdateSegmentPolicy() {
    }

    static boolean admitsWorker(Segment segment) {
        return segment != null && WORKER_SEGMENTS.contains(segment);
    }

    static boolean admitsWorker(Collection<Segment> segments) {
        return segments != null && !segments.isEmpty() && WORKER_SEGMENTS.containsAll(segments);
    }

    static Set<Segment> workerSegments() {
        return WORKER_SEGMENTS;
    }

    /** Whether the declaration contains a segment that must keep its host suffix off a worker. */
    static boolean hostOnly(Collection<Segment> segments) {
        return segments != null && !Collections.disjoint(segments, HOST_ONLY_SEGMENTS);
    }
}
