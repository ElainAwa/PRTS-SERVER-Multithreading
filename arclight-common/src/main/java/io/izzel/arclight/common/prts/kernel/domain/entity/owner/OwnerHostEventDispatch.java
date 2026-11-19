/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.auth.OwnerUnitPermit;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletionException;

/** The host decision channel for plugin events an owner worker raises: the worker queues the event
 * and parks, the parked host dispatches it on its own thread and hands the mutated event back. Only
 * events that need the host thread take this path; the worker never dispatches a plugin event itself. */
public final class OwnerHostEventDispatch {

    private record Request(Event event, CompletableFuture<Void> future) {
    }

    private static final ConcurrentLinkedQueue<Request> REQUESTS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<Boolean> ON_HOST = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private OwnerHostEventDispatch() {
    }

    /** Whether this worker event should be handed to the host instead of dispatched locally. */
    public static boolean active() {
        return OwnerUnitPermit.eventShape() && !ON_HOST.get();
    }

    /** Queues one event for the parked host and waits for its dispatch. */
    public static void dispatch(Event event) {
        Request request = new Request(event, new CompletableFuture<>());
        REQUESTS.add(request);
        StageCounters.note("ownerHostEvent");
        try {
            request.future().join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("owner host event dispatch failed", cause);
        }
    }

    /** Dispatches every queued event on the calling (host) thread; one failing event does not hide
     * the others and the original throwable travels back to its worker. */
    public static void service() {
        Request request;
        while ((request = REQUESTS.poll()) != null) {
            ON_HOST.set(Boolean.TRUE);
            try {
                Bukkit.getPluginManager().callEvent(request.event());
                request.future().complete(null);
            } catch (Throwable failure) {
                request.future().completeExceptionally(failure);
            } finally {
                ON_HOST.remove();
            }
        }
    }

    public static boolean pending() {
        return !REQUESTS.isEmpty();
    }
}