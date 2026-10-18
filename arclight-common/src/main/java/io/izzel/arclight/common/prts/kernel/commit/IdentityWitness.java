/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

/** The one place a line's identity is judged against the declarations its freeze accepted, shared by
 * the write side, the typed entry and a versioned replay. What is proved is provenance: the seat,
 * the artefact, the instance number and the world generation all come from the declaration its own
 * freeze sealed, and the comparison key is derived from that declaration and from no counter. */
public final class IdentityWitness {

    /** Why a line was admitted or refused, and the comparison key when it was admitted. */
    public record Verdict(boolean accepted, String reason, String instance) {

        public Verdict {
            reason = reason == null ? "" : reason;
            instance = instance == null ? "" : instance;
        }
    }

    /** The stable semantic identity of one declared instance: everything a cross-run comparison may
     * read, and nothing a run assigned. The role is the rank the freeze's own declared nodes give
     * this one, so two runs of the same declarations agree on it whatever order the final graph
     * happened to take.
     *
     * <p>An entry key names a cell and its status, and keeps the canonical form the log always
     * carried. A range key names the two folds instead; the forms are never equal and neither
     * parses as the other. */
    public record LogicalKey(String worldId, String domainId, String nodeKey, int role,
                             long packedChunkPos, String targetStatus, String spaceDigest,
                             String statusMapDigest) {

        public LogicalKey {
            worldId = worldId == null ? "" : worldId;
            domainId = domainId == null ? "" : domainId;
            nodeKey = nodeKey == null ? "" : nodeKey;
            targetStatus = targetStatus == null ? "" : targetStatus;
            spaceDigest = spaceDigest == null ? "" : spaceDigest;
            statusMapDigest = statusMapDigest == null ? "" : statusMapDigest;
        }

        /** The key of one accepted entry, in the form the log has carried since before ranges. */
        public LogicalKey(String worldId, String domainId, String nodeKey, int role,
                          long packedChunkPos, String targetStatus) {
            this(worldId, domainId, nodeKey, role, packedChunkPos, targetStatus, "", "");
        }

        /** The key of one range seat: the two folds replace the artefact columns. */
        public static LogicalKey ranged(String worldId, String domainId, String nodeKey, int role,
                                        String spaceDigest, String statusMapDigest) {
            return new LogicalKey(worldId, domainId, nodeKey, role, 0L, "", spaceDigest,
                statusMapDigest);
        }

        /** Whether this key names a range rather than one accepted entry. */
        public boolean ranged() {
            return !spaceDigest.isEmpty() && !statusMapDigest.isEmpty();
        }

        /** The unambiguous canonical form this key is carried and compared as: every text field is
         * its own length, a colon and exactly that many characters, and the numbers are opened by a
         * marker and closed by a semicolon. */
        public String canonical() {
            if (ranged()) {
                return "I2" + segment(worldId) + segment(domainId) + segment(nodeKey) + "#" + role
                    + ";#" + segment(spaceDigest) + ";" + segment(statusMapDigest);
            }
            return "I1" + segment(worldId) + segment(domainId) + segment(nodeKey) + "#" + role + ";#"
                + packedChunkPos + ";" + segment(targetStatus);
        }
    }

    /** One live obligation on a freeze that outlived its replacement: the ticket taken under it, the
     * terminal line that ticket writes, an unread line or a pending item. While it stands the holder
     * keeps the table the line was judged against, so a lawful terminal still exports its closure. */
    public interface Obligation {

        Obligation NONE = () -> {
        };

        void settle();
    }

    /** Where a holder reads the declarations it sealed and the generations that are live now. */
    public interface Source {

        /** Keeps the freeze of one verified seat readable while the obligation that outlived it still
         * names it; a holder that keeps no bounded history answers with a lease that holds nothing. */
        default Obligation oblige(FrozenDeclarations declarations) {
            return Obligation.NONE;
        }

        /** The range seal one seat holds for one cell, or null when this holder has none. */
        default FrozenDeclarations.RangeSeal range(FrozenDeclarations.Seat seat, long cell) {
            return null;
        }

        /** Whether one seat carries any range seal; a line of a cell without one is refused, never
         * read against the entry path. */
        default boolean hasRange(FrozenDeclarations.Seat seat) {
            return false;
        }

        /** Keeps one range seal readable while the line judged against it is still unread. */
        default Obligation oblige(FrozenDeclarations.RangeSeal seal) {
            return Obligation.NONE;
        }

        /** The table of the freeze this seat belongs to, or a table that answers nothing: a holder
         * that answered the newest table to every question would authorize the interval between a
         * plan and its declarations. */
        FrozenDeclarations declarations(String worldId, String domainId, long planSequence);

        long liveWorldEpoch(String worldId);

        /** The generation this plan's own grant answered for one domain, read for the pair
         * {@code (planController.lifecycleKey(), planSequence)} and for no other plan identity. The
         * answer carries the two criteria a judgement applies as two faces - this plan's own record
         * and the live slot read in the same question - and says of each whether it carries a value,
         * a declared absence or nothing that could be read. */
        Authorization liveAuthorization(long planSequence, CommitNamespaces.Namespace planController,
                                        String worldId, String domainId, String nodeKey);

        /** The lifecycle the holder judges lines under right now, or null when the holder is not a
         * controller and only the declared presence of the line's own namespace can be read. */
        CommitNamespaces.Namespace currentController();
    }

    /** What the declared-side read face answered for one plan identity: the pair of the record it
     * answered from, the two faces a judgement reads, and why the source face carries no value. */
    public record Authorization(String lifecycleKey, long planSequence, Face source, Face validity,
                                String refusal) {

        /** No lawful generation is negative: this is what a face carries instead of a number. */
        public static final long NOT_ANSWERED = -1L;

        public Authorization {
            lifecycleKey = lifecycleKey == null ? "" : lifecycleKey;
            refusal = refusal == null ? "" : refusal;
            source = source == null ? Face.notRead() : source;
            validity = validity == null ? Face.notRead() : validity;
        }

        /** An answer whose source face carries this plan's own number. */
        public static Authorization answered(String lifecycleKey, long planSequence, long generation,
                                             Face validity) {
            return new Authorization(lifecycleKey, planSequence, Face.present(generation), validity,
                "");
        }

        /** An answer that refuses the source judgement, with the validity face left unread. */
        public static Authorization refused(String lifecycleKey, long planSequence, Face source,
                                            String reason) {
            return new Authorization(lifecycleKey, planSequence, source, Face.notRead(), reason);
        }

        /** Whether the source judgement passes this answer. */
        public boolean admitted() {
            return refusal.isEmpty() && source.presence == Presence.PRESENT;
        }

        /** One face of an answer: a value, a declared absence, or nothing that could be read. */
        public record Face(Presence presence, long generation) {

            public static Face present(long generation) {
                return new Face(Presence.PRESENT, generation);
            }

            public static Face absent() {
                return new Face(Presence.ABSENT, NOT_ANSWERED);
            }

            public static Face notRead() {
                return new Face(Presence.NOT_READABLE, NOT_ANSWERED);
            }

            public boolean carries() {
                return presence == Presence.PRESENT;
            }
        }

        /** Whether a face carries a value, is a declared absence, or was not read. */
        public enum Presence {
            PRESENT, ABSENT, NOT_READABLE
        }

    }

    /** The source of a chain no holder wired: no typed line is admitted through it. */
    public static final Source NONE = new Source() {

        @Override
        public FrozenDeclarations declarations(String worldId, String domainId, long planSequence) {
            return FrozenDeclarations.none();
        }

        @Override
        public long liveWorldEpoch(String worldId) {
            return CommitLog.NO_WORLD_EPOCH;
        }

        @Override
        public Authorization liveAuthorization(long planSequence,
                                               CommitNamespaces.Namespace planController,
                                               String worldId, String domainId, String nodeKey) {
            return Authorization.refused("", 0L, Authorization.Face.notRead(), "no-record");
        }

        @Override
        public CommitNamespaces.Namespace currentController() {
            return null;
        }
    };

    /** A source that answers the fixed generations a reader holds a seal under. */
    public static Source fixed(FrozenDeclarations declarations, long worldEpoch, long authorization) {
        return new Source() {

            @Override
            public FrozenDeclarations declarations(String worldId, String domainId,
                                                   long planSequence) {
                if (declarations == null || declarations.planSequence() != planSequence) {
                    return FrozenDeclarations.none();
                }
                return declarations;
            }

            @Override
            public long liveWorldEpoch(String worldId) {
                return worldEpoch;
            }

            @Override
            public Authorization liveAuthorization(long planSequence,
                                                   CommitNamespaces.Namespace planController,
                                                   String worldId, String domainId, String nodeKey) {
                if (declarations == null || planController == null
                    || declarations.planSequence() != planSequence
                    || (!declarations.freeze().lifecycleKey().isEmpty()
                        && !declarations.freeze().lifecycleKey()
                            .equals(planController.lifecycleKey()))) {
                    return Authorization.refused("", 0L, Authorization.Face.notRead(),
                        "not-this-declaration");
                }
                return Authorization.answered(planController.lifecycleKey(), planSequence,
                    authorization, Authorization.Face.present(authorization));
            }

            @Override
            public CommitNamespaces.Namespace currentController() {
                return null;
            }
        };
    }

    /** Judges one line against the freeze that ordered it: another plan, a seat the seal wrote nothing
     * for, another node, an instance or generation its own freeze did not authorize and a table of a
     * lifecycle the holder has left are different refusals, every one read from the declaration. */
    public static Verdict verify(FrozenDeclarations declarations, CommitLog.Resolved resolved,
                                 String worldId, String domainId, String nodeKey,
                                 CommitLog.Identity identity, CommitNamespaces.Namespace current) {
        if (declarations == null || resolved == null || identity == null) {
            return refuse("declaration-missing");
        }
        if (declarations.size() == 0) {
            // A table that sealed nothing backs no seat, whatever plan it names: reading it as a
            // table of another plan would report a mismatch where there is no declaration at all.
            return refuse("declaration-missing");
        }
        if (declarations.planSequence() != resolved.planSequence()) {
            return refuse("declaration-plan-mismatch");
        }
        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(worldId, domainId,
            resolved.planSequence(), resolved.position());
        FrozenDeclarations.Entry entry = declarations.entryAt(seat);
        if (entry == null) {
            return refuse("declaration-missing");
        }
        if (!entry.nodeKey().equals(nodeKey)) {
            return refuse("declaration-seat-mismatch");
        }
        if (!identity.declaredTyped()) {
            return refuse("identity-undeclared");
        }
        CommitNamespaces.Namespace worn = identity.controller();
        if (!worn.declared()) {
            return refuse("identity-undeclared");
        }
        if (current != null && !worn.lifecycleKey().equals(current.lifecycleKey())) {
            return refuse("controller-namespace-mismatch");
        }
        if (current != null && !declarations.freeze().lifecycleKey().isEmpty()
            && !declarations.freeze().lifecycleKey().equals(current.lifecycleKey())) {
            // A table of a lifecycle the holder has left is evidence at most, not a declaration.
            return refuse("declaration-lifecycle-mismatch");
        }
        if (identity.chunkPos() != entry.packedChunkPos()) {
            return refuse("chunk-witness-mismatch");
        }
        if (!identity.targetStatus().equals(entry.targetStatus())) {
            return refuse("declaration-status-mismatch");
        }
        if (identity.instanceId() != entry.instanceId()) {
            // The declaration handle mints the identity: a positive number of its own is no proof.
            return refuse("declaration-instance-mismatch");
        }
        if (identity.worldEpoch() != entry.worldEpoch()) {
            return refuse("declaration-epoch-mismatch");
        }
        // Both halves come from the log's one reconstruction point, so the three sites cannot drift
        // into three formulations of the same key. The declaration is the source.
        LogicalKey declared = CommitLog.logicalKeyOf(entry);
        LogicalKey carried = CommitLog.logicalKeyOf(worldId, domainId, nodeKey, entry.role(),
            identity);
        if (declared == null || carried == null || !declared.equals(carried)) {
            return refuse("identity-recompute-mismatch");
        }
        return new Verdict(true, "", declared.canonical());
    }

    /** Judges one line against the range seal its run start published: the seat, cell, per-cell
     * status, instance and generation, with both folds recomputed and the declared range key
     * compared with the one rebuilt from the line. Role, owner and stamp are not re-derived here;
     * they were proved where the seal was built. */
    public static Verdict verifyRange(FrozenDeclarations.RangeSeal range, CommitLog.Resolved resolved,
                                      String worldId, String domainId, String nodeKey,
                                      CommitLog.Identity identity, CommitNamespaces.Namespace current) {
        if (range == null || resolved == null || identity == null) {
            return refuse("declaration-missing");
        }
        if (range.planSequence() != resolved.planSequence() || range.seat() == null
            || !range.seat().equals(new FrozenDeclarations.Seat(worldId, domainId,
                resolved.planSequence(), resolved.position()))
            || !range.nodeKey().equals(nodeKey)) {
            return refuse("range-seat-mismatch");
        }
        if (!identity.declaredTyped()) {
            return refuse("identity-undeclared");
        }
        CommitNamespaces.Namespace worn = identity.controller();
        if (current != null && !worn.lifecycleKey().equals(current.lifecycleKey())) {
            return refuse("controller-namespace-mismatch");
        }
        if (current != null && !range.freeze().lifecycleKey().isEmpty()
            && !range.freeze().lifecycleKey().equals(current.lifecycleKey())) {
            return refuse("declaration-lifecycle-mismatch");
        }
        long[] cells = range.cells();
        if (!RangeDigests.wellFormed(cells)
            || !RangeDigests.spaceDigest(cells).equals(range.spaceDigest())) {
            return refuse("range-digest-mismatch");
        }
        if (!range.contains(identity.chunkPos())) {
            return refuse("chunk-witness-mismatch");
        }
        FrozenDeclarations.CellStatus cell = range.statusFor(identity.chunkPos());
        if (cell == null) {
            return refuse("range-status-missing");
        }
        if (!identity.targetStatus().equals(cell.targetStatus())) {
            return refuse("declaration-status-mismatch");
        }
        if (identity.instanceId() != range.instanceId()) {
            return refuse("declaration-instance-mismatch");
        }
        if (identity.worldEpoch() != range.worldEpoch()) {
            return refuse("declaration-epoch-mismatch");
        }
        if (!stampMatches(range, identity.chunkPos())) {
            return refuse("range-stamp-mismatch");
        }
        if (!RangeDigests.statusMapDigest(range.statuses())
            .equals(range.statusMapDigest())) {
            return refuse("range-digest-mismatch");
        }
        LogicalKey declared = CommitLog.logicalKeyOf(range);
        LogicalKey carried = CommitLog.logicalKeyOfRange(worldId, domainId, nodeKey, range.role(),
            range);
        if (declared == null || carried == null || !declared.equals(carried)) {
            return refuse("identity-recompute-mismatch");
        }
        return new Verdict(true, "", declared.canonical());
    }

    private static boolean stampMatches(FrozenDeclarations.RangeSeal range, long cell) {
        FrozenDeclarations.Stamp stamp = range.stamp();
        if (stamp == null || stamp.cell() != cell || !stamp.world().equals(range.worldId())
            || !stamp.mailbox().equals(range.mailbox()) || stamp.taskOrdinal() <= 0L
            || stamp.batchFreezeOrdinal() != range.freezeOrdinal()
            || stamp.planSequence() != range.planSequence() || stamp.demandToken() <= 0L) {
            return false;
        }
        FrozenDeclarations.CellStatus status = range.statusFor(cell);
        return status != null && status.stamp() != null && status.stamp().equals(stamp);
    }

    /** One obligation that settles both tables a range line was judged against. */
    public static Obligation combine(Obligation first, Obligation second) {
        Obligation left = first == null ? Obligation.NONE : first;
        Obligation right = second == null ? Obligation.NONE : second;
        if (left == Obligation.NONE) {
            return right;
        }
        if (right == Obligation.NONE) {
            return left;
        }
        return new Obligation() {

            private boolean settled;

            @Override
            public synchronized void settle() {
                if (settled) {
                    return;
                }
                settled = true;
                left.settle();
                right.settle();
            }
        };
    }

    /** Reads a canonical form back, for the round trip a carried comparison key is shown to
     * survive. An entry form and a range form are read apart, and a form that does not parse,
     * carries another prefix or names a range with an empty fold is answered with null. */
    public static LogicalKey parse(String text) {
        if (text == null) {
            return null;
        }
        if (text.startsWith("I2")) {
            int[] cursor = {2};
            String world = readSegment(text, cursor);
            String domain = readSegment(text, cursor);
            String node = readSegment(text, cursor);
            Long role = readNumber(text, cursor);
            if (world == null || domain == null || node == null || role == null
                || cursor[0] >= text.length() || text.charAt(cursor[0]) != '#') {
                return null;
            }
            cursor[0]++;
            String space = readSegment(text, cursor);
            if (space == null || space.isEmpty() || cursor[0] >= text.length()
                || text.charAt(cursor[0]) != ';') {
                return null;
            }
            cursor[0]++;
            String statusMap = readSegment(text, cursor);
            if (statusMap == null || statusMap.isEmpty() || cursor[0] != text.length()) {
                return null;
            }
            return LogicalKey.ranged(world, domain, node, role.intValue(), space, statusMap);
        }
        if (!text.startsWith("I1")) {
            return null;
        }
        int[] cursor = {2};
        String world = readSegment(text, cursor);
        String domain = readSegment(text, cursor);
        String node = readSegment(text, cursor);
        Long role = readNumber(text, cursor);
        Long packed = readNumber(text, cursor);
        String status = readSegment(text, cursor);
        if (world == null || domain == null || node == null || role == null || packed == null
            || status == null || cursor[0] != text.length()) {
            return null;
        }
        return new LogicalKey(world, domain, node, role.intValue(), packed, status);
    }

    private static String segment(String value) {
        String text = value == null ? "" : value;
        return text.length() + ":" + text;
    }

    private static String readSegment(String text, int[] cursor) {
        int start = cursor[0];
        int colon = text.indexOf(':', start);
        if (colon < 0) {
            return null;
        }
        int length;
        try {
            length = Integer.parseInt(text.substring(start, colon));
        } catch (NumberFormatException notANumber) {
            return null;
        }
        int from = colon + 1;
        int to = from + length;
        if (length < 0 || to > text.length()) {
            return null;
        }
        cursor[0] = to;
        return text.substring(from, to);
    }

    private static Long readNumber(String text, int[] cursor) {
        int start = cursor[0];
        if (start >= text.length() || text.charAt(start) != '#') {
            return null;
        }
        int end = start + 1;
        while (end < text.length() && text.charAt(end) != ';') {
            end++;
        }
        if (end >= text.length()) {
            return null;
        }
        try {
            long value = Long.parseLong(text.substring(start + 1, end));
            cursor[0] = end + 1;
            return value;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Verdict refuse(String reason) {
        return new Verdict(false, reason, "");
    }

    private IdentityWitness() {
    }
}
