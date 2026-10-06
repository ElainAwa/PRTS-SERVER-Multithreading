/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The claim table of the controlled write channel. A slot is declared for one world, domain and
 * level and names the write set it covers; a write no active slot covers is not a domain write and
 * stays on the default path. A write that is covered is judged against the contract the slot
 * carries, and only a covered write whose contract still holds is captured. Nothing here decides a
 * write by itself: the guard hands a covered write to the judged path it already had. */
public final class WriteControlledSlots {

    /** What one write is to the controlled channel. */
    public enum Verdict {
        DEFAULT_PATH,
        CAPTURED,
        REJECTED
    }

    /** Why a slot was refused when it was declared. These are declaration labels, not write-path
     * reject codes: a refused slot never reaches the write path. */
    public enum Refusal {
        NONE,
        WILDCARD_KEY,
        EMPTY_KEY,
        EMPTY_WRITE_SET,
        PLAN_SEQUENCE_INVALID,
        DUPLICATE_KEY
    }

    /** The key of one controlled slot. The segment stands for the region or block section of the
     * world the slot was frozen for. */
    public record SlotKey(String worldId, String domainId, WriteLevel level, String segment,
                          long planSequence) {

        public boolean wildcard() {
            return isWildcard(worldId) || isWildcard(domainId) || isWildcard(segment);
        }

        public boolean incomplete() {
            return blank(worldId) || blank(domainId) || blank(segment) || level == null;
        }

        /** The order two slots covering the same write are ranked in, so the judgement of a write
         * two slots cover does not depend on the iteration order of the table. */
        public String rank() {
            return worldId + "|" + domainId + "|" + level + "|" + segment + "|" + planSequence;
        }

        private static boolean isWildcard(String value) {
            return "*".equals(value);
        }

        private static boolean blank(String value) {
            return value == null || value.isEmpty();
        }
    }

    /** One declared slot: the write set it covers and the contract a covered write has to satisfy.
     * Every field the design asks a slot to carry is here, and the write set is the only thing that
     * moves a write off the default path. */
    public record Slot(SlotKey key, Object levelRef, String planNodeKey, int position,
                       long expectedVersion, long slotGeneration, long worldEpoch, long expireTick,
                       HolderKind holderKind, String holderSiteId, Set<String> writeSet) {

        public Slot {
            writeSet = writeSet == null ? Set.of() : Set.copyOf(writeSet);
        }

        /** The fold of the declared write set: two slots that declare the same set fold to the same
         * digest, and a write it carries it as the batch identity of the claim. */
        public String writeSetDigest() {
            long hash = FOLD_BASIS;
            for (String key : new TreeSet<>(writeSet)) {
                for (int index = 0; index < key.length(); index++) {
                    hash = (hash ^ key.charAt(index)) * FOLD_PRIME;
                }
            }
            return Long.toHexString(hash);
        }
    }

    /** The facts the table cannot read from its own registries: which world a level belongs to, and
     * which step the frozen plan of the recent ticks holds for a slot. While nothing is wired, no
     * world is named and no step is held, so a covered write is refused instead of captured. */
    public interface Source {

        /** The world a write is happening in, as the platform names it; empty while nothing is wired. */
        String worldOf(Object levelRef);

        /** The step the plan holds for a slot, or null while the plan does not carry it. */
        Step stepOf(String worldId, String domainId, String planNodeKey);

        /** A source that names no world and holds no step. */
        Source NONE = new Source() {

            @Override
            public String worldOf(Object levelRef) {
                return "";
            }

            @Override
            public Step stepOf(String worldId, String domainId, String planNodeKey) {
                return null;
            }
        };

        record Step(long planSequence, int position) {
        }
    }

    /** One judgement. A captured write carries the plan identity the contract resolved; a rejected
     * one names the term that failed. */
    public record Decision(Verdict verdict, SlotKey key, String planNodeKey, long planSequence,
                           int position, String writeSetDigest, String reason) {

        private static Decision defaultPath(String reason) {
            return new Decision(Verdict.DEFAULT_PATH, null, "", 0L, -1, "", reason);
        }
    }

    /** The refusal of one covered write, with the six elements a diagnosis needs: the world, the
     * tick, the holder, the version that was expected and the one that was carried, and the slot. */
    public record WriteRefusal(String reason, String worldId, String domainId, WriteLevel level,
                          String segment, long planSequence, String planNodeKey, String siteId,
                          HolderKind holderKind, long tickIndex, long expectedVersion,
                          long carriedVersion, long worldEpoch, long slotGeneration,
                          String writeSetDigest) {
    }

    private static final long FOLD_BASIS = 0xcbf29ce484222325L;
    private static final long FOLD_PRIME = 0x100000001b3L;

    /** The instance a guard asks while no claim table is wired into it. */
    public static final WriteControlledSlots NOTHING = new WriteControlledSlots(new OwnerRegistry(),
        WriteVersionSlots.NOTHING, new WorldEpochs(), Source.NONE);

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;
    private final WorldEpochs epochs;
    private final Source source;
    private final Map<SlotKey, Slot> slots = new ConcurrentHashMap<>();
    private final Map<SlotKey, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong registered = new AtomicLong();
    private final AtomicLong renewed = new AtomicLong();
    private final AtomicLong advanced = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final Map<Refusal, AtomicLong> refusals = new ConcurrentHashMap<>();
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong defaultPath = new AtomicLong();
    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong notExecutable = new AtomicLong();
    private volatile boolean enabled;
    private volatile Decision lastDecision;
    private volatile WriteRefusal lastRefusal;
    private volatile Slot lastRefusingSlot;

    /** Creates the table. */
    public WriteControlledSlots(OwnerRegistry owners, WriteVersionSlots versions, WorldEpochs epochs,
                                Source source) {
        this.owners = owners;
        this.versions = versions;
        this.epochs = epochs;
        this.source = source;
    }

    /** Decides whether the claim table is consulted at all. Off, every write of the short path is
     * counted as a default-path write and no slot is ever looked up. */
    public void refresh(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Declares one slot. The four ways a slot can be unusable are refused here, when it is
     * declared, and not repaired when a write arrives. */
    public Refusal register(Slot slot) {
        Refusal refusal = refusalOf(slot);
        if (refusal != Refusal.NONE) {
            noteRefusal(refusal);
            return refusal;
        }
        SlotKey key = slot.key();
        Long seated = generations.get(key);
        if (seated != null && seated == slot.slotGeneration()) {
            noteRefusal(Refusal.DUPLICATE_KEY);
            return Refusal.DUPLICATE_KEY;
        }
        if (seated != null) {
            renewed.incrementAndGet();
        }
        slots.put(key, slot);
        generations.put(key, slot.slotGeneration());
        registered.incrementAndGet();
        return Refusal.NONE;
    }

    /** Retires the slot of one key. The writes it covered fall back to the default path. */
    public boolean retire(SlotKey key) {
        generations.remove(key);
        return slots.remove(key) != null;
    }

    /** Retires the generation of one key without seating a new slot: everything the seated slot
     * covers is refused from here on, until a slot of the new generation is declared. */
    public long advance(SlotKey key) {
        long minted = generations.merge(key, 1L, Long::sum);
        advanced.incrementAndGet();
        return minted;
    }

    /** Judges one write of one write path against the table. */
    public Decision judge(Object levelRef, String domainKey, long tickIndex) {
        if (levelRef == null || domainKey == null || domainKey.isEmpty()) {
            notExecutable.incrementAndGet();
            return lastDecision = Decision.defaultPath("NOT-EXECUTABLE");
        }
        attempts.incrementAndGet();
        Slot slot = covering(levelRef, domainKey);
        if (slot == null) {
            defaultPath.incrementAndGet();
            return lastDecision = Decision.defaultPath("");
        }
        String world = source.worldOf(levelRef);
        Source.Step step = source.stepOf(slot.key().worldId(), slot.key().domainId(),
            slot.planNodeKey());
        WriteRefusal refusal = inspect(slot, world, step, tickIndex);
        if (refusal != null) {
            rejected.incrementAndGet();
            lastRefusal = refusal;
            lastRefusingSlot = slot;
            return lastDecision = new Decision(Verdict.REJECTED, slot.key(), slot.planNodeKey(), 0L,
                -1, slot.writeSetDigest(), refusal.reason());
        }
        captured.incrementAndGet();
        return lastDecision = new Decision(Verdict.CAPTURED, slot.key(), slot.planNodeKey(),
            step.planSequence(), step.position(), slot.writeSetDigest(), "");
    }

    /** The slot covering one write: an active slot that was declared for the very level the write
     * arrives on and whose write set names the write path it was made against. */
    private Slot covering(Object levelRef, String domainKey) {
        if (!enabled) {
            return null;
        }
        Slot found = null;
        for (Slot slot : slots.values()) {
            if (slot.levelRef() != levelRef || !slot.writeSet().contains(domainKey)) {
                continue;
            }
            if (found == null || slot.key().rank().compareTo(found.key().rank()) < 0) {
                found = slot;
            }
        }
        return found;
    }

    /** The contract of a covered write: the world, the holder the token names, the version the slot
     * and the token expect, the generation, the expiry and the plan step the slot was frozen with.
     * A term that cannot be read at all fails the same way a wrong one does. */
    private WriteRefusal inspect(Slot slot, String world, Source.Step step, long tickIndex) {
        SlotKey key = slot.key();
        long carried = versions.carried(key.worldId(), key.level(), key.domainId());
        if (world.isEmpty() || !world.equals(key.worldId())) {
            return refusal(slot, "world", carried, tickIndex);
        }
        OwnerToken token = owners.lookup(key.worldId(), key.level(), key.domainId()).orElse(null);
        if (token == null || token.holderKind() != slot.holderKind()
            || !token.holderSiteId().equals(slot.holderSiteId())) {
            return refusal(slot, "owner", carried, tickIndex);
        }
        if (token.expiredAt(tickIndex) || slot.expireTick() <= 0L || tickIndex >= slot.expireTick()) {
            return refusal(slot, "expiry", carried, tickIndex);
        }
        if (carried <= WriteVersionSlots.NOT_CARRIED || carried != slot.expectedVersion()
            || token.expectedVersion() != slot.expectedVersion()) {
            return refusal(slot, "version", carried, tickIndex);
        }
        Long generation = generations.get(key);
        if (generation == null || generation != slot.slotGeneration()) {
            return refusal(slot, "generation", carried, tickIndex);
        }
        long live = epochs.epochOf(key.worldId());
        if (live <= 0L || live != slot.worldEpoch()) {
            return refusal(slot, "world_epoch", carried, tickIndex);
        }
        if (slot.planNodeKey().isEmpty() || step == null) {
            return refusal(slot, "plan", carried, tickIndex);
        }
        if (step.planSequence() != key.planSequence()) {
            return refusal(slot, "plan_sequence", carried, tickIndex);
        }
        return null;
    }

    private WriteRefusal refusal(Slot slot, String reason, long carried, long tickIndex) {
        SlotKey key = slot.key();
        return new WriteRefusal(reason, key.worldId(), key.domainId(), key.level(), key.segment(),
            key.planSequence(), slot.planNodeKey(), slot.holderSiteId(), slot.holderKind(), tickIndex,
            slot.expectedVersion(), carried, slot.worldEpoch(), slot.slotGeneration(),
            slot.writeSetDigest());
    }

    private static Refusal refusalOf(Slot slot) {
        if (slot == null || slot.key() == null) {
            return Refusal.EMPTY_KEY;
        }
        if (slot.key().wildcard()) {
            return Refusal.WILDCARD_KEY;
        }
        if (slot.key().incomplete()) {
            return Refusal.EMPTY_KEY;
        }
        if (slot.writeSet().isEmpty()) {
            return Refusal.EMPTY_WRITE_SET;
        }
        if (slot.key().planSequence() <= 0L) {
            return Refusal.PLAN_SEQUENCE_INVALID;
        }
        return Refusal.NONE;
    }

    private void noteRefusal(Refusal refusal) {
        refused.incrementAndGet();
        refusals.computeIfAbsent(refusal, key -> new AtomicLong()).incrementAndGet();
    }

    public int slots() {
        return slots.size();
    }

    public long registeredCount() {
        return registered.get();
    }

    public long renewedCount() {
        return renewed.get();
    }

    public long advancedCount() {
        return advanced.get();
    }

    public long refusedCount() {
        return refused.get();
    }

    public long refusedCount(Refusal refusal) {
        AtomicLong cell = refusals.get(refusal);
        return cell == null ? 0L : cell.get();
    }

    public long attemptsCount() {
        return attempts.get();
    }

    public long defaultPathCount() {
        return defaultPath.get();
    }

    public long capturedCount() {
        return captured.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** How often a caller handed the table nothing to judge. It is counted on its own so the three
     * verdicts stay the three a write can leave with. */
    public long notExecutableCount() {
        return notExecutable.get();
    }

    /** The conservation of the claim face: every write the table judged left by exactly one of the
     * three verdicts, so a claim cannot hide a write and no write is judged twice. */
    public boolean conservationHolds() {
        return captured.get() + defaultPath.get() + rejected.get() == attempts.get();
    }

    public Decision lastDecision() {
        return lastDecision;
    }

    public WriteRefusal lastRefusal() {
        return lastRefusal;
    }

    /** The slot the last refusal was taken against, so a reader can name the slot as a whole. */
    public Slot lastRefusingSlot() {
        return lastRefusingSlot;
    }

    /** Clears the counters and the slot table. */
    public void reset() {
        slots.clear();
        generations.clear();
        registered.set(0L);
        renewed.set(0L);
        advanced.set(0L);
        refused.set(0L);
        refusals.clear();
        attempts.set(0L);
        defaultPath.set(0L);
        captured.set(0L);
        rejected.set(0L);
        notExecutable.set(0L);
        lastDecision = null;
        lastRefusal = null;
        lastRefusingSlot = null;
    }
}
