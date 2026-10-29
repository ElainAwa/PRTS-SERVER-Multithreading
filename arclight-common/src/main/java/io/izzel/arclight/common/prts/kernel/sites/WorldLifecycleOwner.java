/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import java.util.LinkedHashMap;
import java.util.Map;

/** The one thread allowed to move one world through the seven lifecycle states. A request from any
 * other thread, or a transition the state machine does not allow, is refused and counted; no method
 * parks the caller or waits for another thread. */
public final class WorldLifecycleOwner {

    /** The seven states of the world lifecycle. */
    public enum State {
        CREATING,
        LOADING,
        READY,
        PINNED,
        UNLOADING,
        RECLAIMING,
        GONE
    }

    private final Map<String, State> worlds = new LinkedHashMap<>();
    private Thread owner;
    private long denied;
    private long transitions;

    /** Binds the one owner thread. A second different binding is refused and changes nothing. */
    public synchronized boolean bindOwner(Thread thread) {
        if (thread == null) {
            return false;
        }
        if (owner == null) {
            owner = thread;
            return true;
        }
        return owner == thread;
    }

    /** Requests one creation from the owner. An existing non-GONE world is refused; a GONE world
     * can be created again. */
    public synchronized boolean requestCreate(String worldId) {
        if (!ownerThread()) {
            return deny();
        }
        State state = worlds.get(worldId);
        if (state != null && state != State.GONE) {
            return deny();
        }
        worlds.put(worldId, State.CREATING);
        transitions++;
        return true;
    }

    /** Requests one unload from the owner. Only a READY or PINNED world may start unloading. */
    public synchronized boolean requestUnload(String worldId) {
        if (!ownerThread()) {
            return deny();
        }
        State state = worlds.get(worldId);
        if (state != State.READY && state != State.PINNED) {
            return deny();
        }
        worlds.put(worldId, State.UNLOADING);
        transitions++;
        return true;
    }

    /** Advances one world along the one legal edge out of its current state. */
    public synchronized boolean transition(String worldId, State target) {
        if (!ownerThread()) {
            return deny();
        }
        State current = worlds.get(worldId);
        if (current == null || target == null || !legal(current, target)) {
            return deny();
        }
        worlds.put(worldId, target);
        transitions++;
        return true;
    }

    public synchronized State stateOf(String worldId) {
        return worlds.get(worldId);
    }

    public synchronized int worldCount() {
        return worlds.size();
    }

    public synchronized long deniedCount() {
        return denied;
    }

    public synchronized long transitionCount() {
        return transitions;
    }

    /** Clears the window counters only; the loaded world states survive a readout reset, so a
     * later legal unload is still recognized instead of being refused as unknown. */
    public synchronized void reset() {
        denied = 0L;
        transitions = 0L;
    }

    /** Drops every world state; only a test that owns a fresh instance uses this. */
    synchronized void forgetAll() {
        worlds.clear();
        reset();
    }

    private boolean ownerThread() {
        return owner != null && owner == Thread.currentThread();
    }

    private boolean deny() {
        denied++;
        return false;
    }

    private static boolean legal(State current, State target) {
        return switch (current) {
            case CREATING -> target == State.LOADING;
            case LOADING -> target == State.READY;
            case READY -> target == State.PINNED || target == State.UNLOADING;
            case PINNED -> target == State.READY || target == State.UNLOADING;
            case UNLOADING -> target == State.RECLAIMING;
            case RECLAIMING -> target == State.GONE;
            case GONE -> false;
        };
    }
}
