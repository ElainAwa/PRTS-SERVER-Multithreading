/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.nativeext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The lifetime of one native handle across the JNI boundary: ALLOCATED -> IN_NATIVE_STEP ->
 * STEP_RETURNED -> RELEASED, with QUARANTINED_IN_FLIGHT while a timeout overlapped the running
 * native call. A release request during IN_NATIVE_STEP never frees or reuses the segment; it
 * quarantines the handle until the native call really returns, and a stateful step of one
 * (engine, step) may enter the native call once only, never again after a timeout and never as a
 * Java replay. */
public final class NativeHandleLifetime {

    /** The lifecycle state of one native handle. */
    public enum State {
        ALLOCATED,
        IN_NATIVE_STEP,
        STEP_RETURNED,
        QUARANTINED_IN_FLIGHT,
        RELEASED
    }

    /** What one release request did. */
    public record Release(boolean released, State state, String reason) {
    }

    private final Map<String, State> handles = new LinkedHashMap<>();
    private final Map<String, Boolean> enteredSteps = new LinkedHashMap<>();
    private long quarantined;
    private long replayRefused;

    /** Allocates one handle once. */
    public synchronized boolean allocate(String handleId) {
        Objects.requireNonNull(handleId, "handleId");
        return handles.putIfAbsent(handleId, State.ALLOCATED) == null;
    }

    /** Enters the native step once per (engine, step); a second entry is a replay refusal. */
    public synchronized boolean enterNative(String handleId, String engineId, long stepOrdinal) {
        String stepKey = engineId + ":" + stepOrdinal;
        if (enteredSteps.containsKey(stepKey)) {
            replayRefused++;
            return false;
        }
        if (handles.get(handleId) != State.ALLOCATED) {
            replayRefused++;
            return false;
        }
        handles.put(handleId, State.IN_NATIVE_STEP);
        enteredSteps.put(stepKey, Boolean.TRUE);
        return true;
    }

    /** Demands the release of one handle; an in-flight native step is quarantined, not freed. */
    public synchronized Release requestRelease(String handleId) {
        State state = handles.get(handleId);
        if (state == null) {
            return new Release(false, null, "unknown-handle");
        }
        if (state == State.IN_NATIVE_STEP) {
            handles.put(handleId, State.QUARANTINED_IN_FLIGHT);
            quarantined++;
            return new Release(false, State.QUARANTINED_IN_FLIGHT, "quarantined-in-flight");
        }
        if (state == State.QUARANTINED_IN_FLIGHT) {
            return new Release(false, State.QUARANTINED_IN_FLIGHT, "still-in-flight");
        }
        handles.put(handleId, State.RELEASED);
        return new Release(true, State.RELEASED, "released");
    }

    /** Records the physical return of the native call; a quarantined handle turns returned. */
    public synchronized boolean nativeReturned(String handleId) {
        State state = handles.get(handleId);
        if (state != State.IN_NATIVE_STEP && state != State.QUARANTINED_IN_FLIGHT) {
            return false;
        }
        handles.put(handleId, State.STEP_RETURNED);
        return true;
    }

    /** Frees a returned handle; a handle still in flight or quarantined is never freed. */
    public synchronized Release releaseReturned(String handleId) {
        State state = handles.get(handleId);
        if (state != State.STEP_RETURNED) {
            return new Release(false, state, "not-returned");
        }
        handles.put(handleId, State.RELEASED);
        return new Release(true, State.RELEASED, "released");
    }

    public synchronized State state(String handleId) {
        return handles.get(handleId);
    }

    public synchronized long quarantined() {
        return quarantined;
    }

    public synchronized long replayRefused() {
        return replayRefused;
    }
}
