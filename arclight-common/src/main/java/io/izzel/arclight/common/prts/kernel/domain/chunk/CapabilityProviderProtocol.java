/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The capability provider protocol: one I/O node suspends in the window account's WAIT_RESULT
 * bucket instead of occupying a CPU worker, the provider answers asynchronously under a holder
 * generation, a completion of an older generation is refused and wakes no successor, and a ready
 * completion wakes exactly the successor the submission named. An unregistered wait point never
 * runs, and no method here waits, joins or reads a world.
 */
public final class CapabilityProviderProtocol {

    /** The node one provider answers: world, chunk, holder generation and capability. */
    public record NodeKey(String worldId, long chunkPos, long holderGen, String capability) {

        public NodeKey {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(capability, "capability");
            if (worldId.isEmpty() || capability.isEmpty()) {
                throw new IllegalArgumentException("a capability node names its world and name");
            }
        }

        /** The wait point a submission needs registered before it may run. */
        public String waitPoint() {
            return "chunk:capability:" + capability;
        }
    }

    /** The provider of one capability; it answers later through the given completion. */
    @FunctionalInterface
    public interface Provider {

        void request(NodeKey key, Completion completion);
    }

    /** The asynchronous answer of one provider. */
    public interface Completion {

        void completed(long holderGen, byte[] payload);

        void failed(long holderGen, String reason);
    }

    /** One suspended node: its account unit and the successor a real completion wakes. */
    public record Token(String unitId, NodeKey key, Runnable successor) {
    }

    /** What one submission did. */
    public record Submission(Token token, boolean suspended, String reason) {
    }

    /** What one completion did. */
    public record Answer(boolean woken, boolean stale, String reason) {
    }

    private final WorkAccount account;
    private final Predicate<String> waitPointRegistered;
    private final Map<String, Token> pending = new LinkedHashMap<>();
    private long staleCompletions;
    private long woken;
    private long refusedUnregistered;
    private long failed;

    /** A protocol over one window account and the wait-point predicate it must satisfy. */
    public CapabilityProviderProtocol(WorkAccount account,
                                      Predicate<String> waitPointRegistered) {
        this.account = Objects.requireNonNull(account, "account");
        this.waitPointRegistered = Objects.requireNonNull(waitPointRegistered,
            "waitPointRegistered");
    }

    /**
     * Suspends one capability node: the account unit moves to WAIT_RESULT, the provider is asked
     * once, and the caller returns without a worker thread held. A wait point that is not
     * registered refuses before any unit is offered.
     */
    public synchronized Submission submit(NodeKey key, Provider provider, Runnable successor) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(successor, "successor");
        if (!waitPointRegistered.test(key.waitPoint())) {
            refusedUnregistered++;
            return new Submission(null, false, "wait-point-unregistered");
        }
        String unitId = "chunk:" + key.capability() + ":" + key.holderGen() + ":"
            + key.chunkPos();
        if (!account.offer(unitId, key.capability(), key.worldId(), "chunk")) {
            failed++;
            return new Submission(null, false, "unit-duplicate");
        }
        account.markWaitResult(unitId);
        Token token = new Token(unitId, key, successor);
        pending.put(unitId, token);
        provider.request(key, completion(token));
        return new Submission(token, true, "wait-result");
    }

    /** One provider success: an older holder generation is refused and wakes nothing. */
    public synchronized Answer completed(Token token, long holderGen, byte[] payload) {
        Objects.requireNonNull(token, "token");
        if (!accepts(token, holderGen)) {
            return new Answer(false, true, "stale-holder-generation");
        }
        pending.remove(token.unitId());
        account.close(token.unitId(), WorkAccount.Outcome.DONE, "capability-completed");
        woken++;
        token.successor().run();
        return new Answer(true, false, "woken");
    }

    /** One provider failure: the unit closes as a fault and no successor runs. */
    public synchronized Answer failed(Token token, long holderGen, String reason) {
        Objects.requireNonNull(token, "token");
        if (!accepts(token, holderGen)) {
            return new Answer(false, true, "stale-holder-generation");
        }
        pending.remove(token.unitId());
        account.close(token.unitId(), WorkAccount.Outcome.FAULT,
            reason == null ? "capability-failed" : reason);
        failed++;
        return new Answer(false, false, "failed");
    }

    /** Whether one unit is suspended right now. */
    public synchronized boolean suspended(String unitId) {
        return pending.containsKey(unitId);
    }

    public synchronized int pending() {
        return pending.size();
    }

    public synchronized long staleCompletions() {
        return staleCompletions;
    }

    public synchronized long woken() {
        return woken;
    }

    public synchronized long refusedUnregistered() {
        return refusedUnregistered;
    }

    private boolean accepts(Token token, long holderGen) {
        Token installed = pending.get(token.unitId());
        if (installed == null) {
            return false;
        }
        if (holderGen != installed.key().holderGen()) {
            pending.remove(token.unitId());
            staleCompletions++;
            return false;
        }
        return true;
    }

    private Completion completion(Token token) {
        return new Completion() {
            @Override
            public void completed(long holderGen, byte[] payload) {
                CapabilityProviderProtocol.this.completed(token, holderGen, payload);
            }

            @Override
            public void failed(long holderGen, String reason) {
                CapabilityProviderProtocol.this.failed(token, holderGen, reason);
            }
        };
    }
}
