/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.quota;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure quota decision contract: four families map to a non-cartesian subset of levels, every
 * overrun decision is RUN, DEFER or DECLINE, and RUN/DEFER carry a token plus a receipt precise
 * enough to replay or reject. It performs no action, changes no schedule and publishes no key. The
 * token/epoch/receipt state is owned by the server thread and every entry point is synchronized;
 * the processed map is bounded and an epoch retirement drops its receipts. */
public final class QuotaPolicy {

    /** The four quota families. B4 is deliberately absent; it waits for a safe event contract. */
    public enum Family {
        MACHINE,
        DEVICE,
        ENTITY,
        NET
    }

    /** The three decisions of the contract. */
    public enum Decision {
        RUN,
        DEFER,
        DECLINE
    }

    /** The result carried by a receipt. */
    public enum Result {
        OK,
        NOOP,
        DEFERRED,
        DECLINED,
        FAILED
    }

    /** A world-scoped bucket or the global bucket the network codec can use. */
    public record Scope(String id, boolean global) {
        public Scope {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("a quota scope needs an id");
            }
        }

        public static Scope world(String worldId) {
            return new Scope(worldId, false);
        }

        public static Scope global(String id) {
            return new Scope(id, true);
        }
    }

    /** One overrun request; measured/limit are milliseconds and tick is diagnostic only. */
    public record Request(Family family, DegradeLevel level, Scope scope, long epoch, long tick,
                          double measured, double limit, boolean safeAction, boolean deferable) {
    }

    /** One precise decision receipt; action/rollback are names, never a dispatch. */
    public record Receipt(Family family, Scope scope, long epoch, DegradeLevel level, long token,
                          long tick, double measured, double limit, Decision decision, Result result,
                          String action, String rollback) {
    }

    private static final int PROCESSED_LIMIT = 4096;
    private static final Map<String, Long> EPOCHS = new LinkedHashMap<>();
    private static final Map<Long, Receipt> PROCESSED = new LinkedHashMap<>();
    private static long nextToken = 1L;

    private QuotaPolicy() {
    }

    /** Returns the current epoch of a scope, starting at zero and moving forward on retirement. */
    public static synchronized long epoch(String scopeId) {
        return EPOCHS.getOrDefault(scopeId, 0L);
    }

    /** Retires the scope's epoch and drops every processed receipt of it; old tokens cannot replay. */
    public static synchronized long retireEpoch(String scopeId) {
        long next = epoch(scopeId) + 1L;
        EPOCHS.put(scopeId, next);
        PROCESSED.entrySet().removeIf(entry -> entry.getValue().scope().id().equals(scopeId));
        return next;
    }

    /** A non-overrun request keeps the original behaviour and carries no token. */
    public static Receipt decide(Request request) {
        if (request == null || request.family() == null || request.level() == null
            || request.scope() == null) {
            return new Receipt(request == null ? null : request.family(),
                request == null ? null : request.scope(), 0L,
                request == null ? null : request.level(), 0L, 0L, 0.0, 0.0,
                Decision.DECLINE, Result.FAILED, "none", "none");
        }
        if (request.measured() <= request.limit()) {
            return new Receipt(request.family(), request.scope(), request.epoch(), request.level(),
                0L, request.tick(), request.measured(), request.limit(), Decision.RUN, Result.NOOP,
                "none", "none");
        }
        if (request.family() == Family.MACHINE) {
            return decline(request, "machine-no-safe-action");
        }
        if (request.family() == Family.NET && !request.scope().global()) {
            return decline(request, "net-global-scope");
        }
        if (!allowed(request.family(), request.level())) {
            return decline(request, "family-level");
        }
        if (!request.safeAction()) {
            return decline(request, "no-safe-action");
        }
        if (request.deferable()) {
            return decline(request, "defer-not-implemented");
        }
        return withToken(request, Decision.RUN, Result.OK, "act", "epoch");
    }

    /** Replays a token exactly once; a retired epoch, unknown token or mismatched scope is FAILED. */
    public static synchronized Receipt replay(Scope scope, long epoch, long token) {
        Receipt receipt = PROCESSED.get(token);
        if (receipt == null) {
            return new Receipt(null, scope, epoch, null, token, 0L, 0.0, 0.0,
                Decision.DECLINE, Result.FAILED, "replay-missing", "none");
        }
        if (scope == null || !receipt.scope().id().equals(scope.id())
            || receipt.epoch() != epoch) {
            return new Receipt(receipt.family(), scope, epoch, receipt.level(), token, 0L,
                receipt.measured(), receipt.limit(), Decision.DECLINE, Result.FAILED,
                "replay-mismatch", "none");
        }
        return receipt;
    }

    static boolean allowed(Family family, DegradeLevel level) {
        if (family == null || level == null || level == DegradeLevel.NONE) {
            return false;
        }
        return switch (family) {
            case DEVICE, ENTITY -> level == DegradeLevel.B3;
            case NET, MACHINE -> false;
        };
    }

    private static Receipt decline(Request request, String action) {
        return new Receipt(request.family(), request.scope(), request.epoch(), request.level(), 0L,
            request.tick(), request.measured(), request.limit(), Decision.DECLINE,
            Result.DECLINED, action, "none");
    }

    private static synchronized Receipt withToken(Request request, Decision decision, Result result,
                                                  String action, String rollback) {
        long token = nextToken++;
        Receipt receipt = new Receipt(request.family(), request.scope(), request.epoch(),
            request.level(), token, request.tick(), request.measured(), request.limit(), decision,
            result, action, rollback);
        PROCESSED.put(token, receipt);
        if (PROCESSED.size() > PROCESSED_LIMIT) {
            Iterator<Long> oldest = PROCESSED.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        return receipt;
    }

    /** Test/cleanup reset; no production path calls it. */
    public static synchronized void reset() {
        EPOCHS.clear();
        PROCESSED.clear();
        nextToken = 1L;
    }
}
