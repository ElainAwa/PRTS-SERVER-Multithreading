/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.be;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** The shadow conservation check of one hopper transfer: it snapshots the real item counts of both
 * containers before and after the host's own move, builds a transfer transaction and asks the
 * conservation contract whether every moved item is balanced. It never writes a container, changes
 * an event result or retries a transfer; an unbalanced or unreadable observation is only counted. */
public final class HopperConservationShadow {

    /** The two container snapshots taken before the host move. */
    public record Snapshot(Map<String, Long> source, Map<String, Long> destination) {
    }

    /** The balanced/unbalanced answer of one observation. */
    public record Check(boolean balanced, long movedOut, long movedIn, String reason) {
    }

    private static final AtomicLong OBSERVED = new AtomicLong();
    private static final AtomicLong MISMATCHED = new AtomicLong();
    private static final AtomicLong FAULTED = new AtomicLong();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private HopperConservationShadow() {
    }

    /** Snapshots both containers before the host move; null while the kernel is disabled. */
    public static Snapshot begin(Container source, Container destination) {
        if (!KernelSettings.enabled()) {
            return null;
        }
        try {
            return new Snapshot(counts(source), counts(destination));
        } catch (Throwable unreadable) {
            FAULTED.incrementAndGet();
            return null;
        }
    }

    /** Compares the post-move counts with the snapshot; the result is counted, never acted upon. */
    public static Check end(Snapshot before, Container source, Container destination) {
        if (before == null) {
            return null;
        }
        try {
            return compare(before.source(), before.destination(), counts(source), counts(destination));
        } catch (Throwable unreadable) {
            FAULTED.incrementAndGet();
            return null;
        }
    }

    /** The pure comparison: moved-out items are inputs, moved-in items are outputs, per item key. */
    public static Check compare(Map<String, Long> sourceBefore, Map<String, Long> destinationBefore,
                                Map<String, Long> sourceAfter, Map<String, Long> destinationAfter) {
        Map<String, Long> movedOut = new LinkedHashMap<>();
        Map<String, Long> movedIn = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : sourceBefore.entrySet()) {
            long delta = entry.getValue() - sourceAfter.getOrDefault(entry.getKey(), 0L);
            if (delta > 0L) {
                movedOut.put(entry.getKey(), delta);
            }
        }
        for (Map.Entry<String, Long> entry : destinationAfter.entrySet()) {
            long delta = entry.getValue() - destinationBefore.getOrDefault(entry.getKey(), 0L);
            if (delta > 0L) {
                movedIn.put(entry.getKey(), delta);
            }
        }
        List<TransferConservationContract.Amount> inputs = movedOut.entrySet().stream()
            .map(entry -> new TransferConservationContract.Amount(
                TransferConservationContract.ResourceKind.ITEM, entry.getKey(), entry.getValue()))
            .toList();
        List<TransferConservationContract.Amount> outputs = movedIn.entrySet().stream()
            .map(entry -> new TransferConservationContract.Amount(
                TransferConservationContract.ResourceKind.ITEM, entry.getKey(), entry.getValue()))
            .toList();
        TransferConservationContract.Transaction transaction =
            new TransferConservationContract.Transaction("hopper-shadow-" + SEQUENCE.incrementAndGet(),
                "hopper-shadow", inputs, outputs);
        Map<TransferConservationContract.ResourceKind, Long> delta =
            TransferConservationContract.balanceDelta(transaction);
        boolean balanced = delta.values().stream().allMatch(value -> value == 0L);
        long out = inputs.stream().mapToLong(TransferConservationContract.Amount::quantity).sum();
        long in = outputs.stream().mapToLong(TransferConservationContract.Amount::quantity).sum();
        OBSERVED.incrementAndGet();
        if (!balanced) {
            MISMATCHED.incrementAndGet();
        }
        return new Check(balanced, out, in, balanced ? "balanced" : "unbalanced");
    }

    /** Counts the real stacks of one container, keyed by the item registry name. */
    public static Map<String, Long> counts(Container container) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (container == null) {
            return counts;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            counts.merge(key(stack.getItem()), (long) stack.getCount(), Long::sum);
        }
        return counts;
    }

    public static long observed() {
        return OBSERVED.get();
    }

    public static long mismatched() {
        return MISMATCHED.get();
    }

    public static long faulted() {
        return FAULTED.get();
    }

    public static void reset() {
        OBSERVED.set(0L);
        MISMATCHED.set(0L);
        FAULTED.set(0L);
        SEQUENCE.set(0L);
    }

    private static String key(Item item) {
        try {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        } catch (Throwable unavailable) {
            return String.valueOf(item);
        }
    }
}
