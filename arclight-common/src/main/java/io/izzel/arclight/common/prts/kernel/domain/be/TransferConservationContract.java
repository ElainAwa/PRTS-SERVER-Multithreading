/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.be;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The conservation transaction of one device-network transfer or recipe conversion: inputs are
 * checked against the source, outputs against the target, the decision event is admitted before any
 * effect, the deductions and additions apply atomically, and a failed or cancelled transaction
 * rolls back to its snapshot so no half deduction or duplication stays. A shared network is keyed by
 * its network id, never by a chunk name. */
public final class TransferConservationContract {

    /** What one transaction moves or converts. */
    public enum ResourceKind {
        ITEM,
        FLUID,
        ENERGY,
        RECIPE
    }

    /** One quantity of one resource key. */
    public record Amount(ResourceKind kind, String key, long quantity) {

        public Amount {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
            if (key.isEmpty()) {
                throw new IllegalArgumentException("a transfer amount names its key");
            }
            if (quantity <= 0L) {
                throw new IllegalArgumentException("a transfer amount is positive");
            }
        }
    }

    /** One transaction over one shared network: its deductions and additions must balance. */
    public record Transaction(String transactionId, String networkId, List<Amount> inputs,
                              List<Amount> outputs) {

        public Transaction {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(outputs, "outputs");
            if (transactionId.isEmpty() || networkId.isEmpty()) {
                throw new IllegalArgumentException("a transaction names itself and its network");
            }
        }
    }

    /** One applied delta, kept so a rollback reverses exactly what was written. */
    private record Applied(ResourceKind kind, String key, long delta) {
    }

    /** Where the balances live; the contract only reads and applies deltas. */
    public interface Ledger {

        long balance(ResourceKind kind, String key);

        boolean canAccept(ResourceKind kind, String key, long quantity);

        void apply(ResourceKind kind, String key, long delta);
    }

    /** What one commit did. */
    public enum Result {
        COMMITTED,
        DUPLICATE,
        REFUSED_EVENT,
        REFUSED_INSUFFICIENT,
        REFUSED_CAPACITY,
        REFUSED_UNBALANCED,
        PARTIAL_FAULT
    }

    /** The commit answer with its per-kind conservation delta and whether a prefix was isolated. */
    public record Outcome(Result result, Map<ResourceKind, Long> conservationDelta,
                          boolean partialFault) {
    }

    private final Ledger ledger;
    private final Map<String, Result> seen = new LinkedHashMap<>();
    private long committed;
    private long refused;
    private long partialFaults;

    public TransferConservationContract(Ledger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /** Commits one transaction atomically; a duplicate id never applies twice. */
    public synchronized Outcome commit(Transaction transaction, boolean eventAllowed) {
        Objects.requireNonNull(transaction, "transaction");
        Result known = seen.get(transaction.transactionId());
        if (known != null) {
            return new Outcome(Result.DUPLICATE, Map.of(), false);
        }
        if (!eventAllowed) {
            return refuse(Result.REFUSED_EVENT);
        }
        Map<ResourceKind, Long> delta = balanceDelta(transaction);
        for (Map.Entry<ResourceKind, Long> entry : delta.entrySet()) {
            if (entry.getValue() != 0L) {
                return refuse(Result.REFUSED_UNBALANCED);
            }
        }
        for (Amount input : transaction.inputs()) {
            if (ledger.balance(input.kind(), input.key()) < input.quantity()) {
                return refuse(Result.REFUSED_INSUFFICIENT);
            }
        }
        for (Amount output : transaction.outputs()) {
            if (!ledger.canAccept(output.kind(), output.key(), output.quantity())) {
                return refuse(Result.REFUSED_CAPACITY);
            }
        }
        List<Applied> applied = new ArrayList<>();
        try {
            for (Amount input : transaction.inputs()) {
                ledger.apply(input.kind(), input.key(), -input.quantity());
                applied.add(new Applied(input.kind(), input.key(), -input.quantity()));
            }
            for (Amount output : transaction.outputs()) {
                ledger.apply(output.kind(), output.key(), output.quantity());
                applied.add(new Applied(output.kind(), output.key(), output.quantity()));
            }
        } catch (RuntimeException failure) {
            for (Applied amount : applied) {
                ledger.apply(amount.kind(), amount.key(), -amount.delta());
            }
            partialFaults++;
            seen.put(transaction.transactionId(), Result.PARTIAL_FAULT);
            return new Outcome(Result.PARTIAL_FAULT, Map.of(), true);
        }
        seen.put(transaction.transactionId(), Result.COMMITTED);
        committed++;
        return new Outcome(Result.COMMITTED, Map.of(), false);
    }

    /** The per-kind input/output delta; a zero delta is the conservation witness. */
    public static Map<ResourceKind, Long> balanceDelta(Transaction transaction) {
        Map<ResourceKind, Long> delta = new LinkedHashMap<>();
        for (Amount input : transaction.inputs()) {
            delta.merge(input.kind(), -input.quantity(), Long::sum);
        }
        for (Amount output : transaction.outputs()) {
            delta.merge(output.kind(), output.quantity(), Long::sum);
        }
        return delta;
    }

    public synchronized long committed() {
        return committed;
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long partialFaults() {
        return partialFaults;
    }

    private Outcome refuse(Result result) {
        refused++;
        return new Outcome(result, Map.of(), false);
    }

}
