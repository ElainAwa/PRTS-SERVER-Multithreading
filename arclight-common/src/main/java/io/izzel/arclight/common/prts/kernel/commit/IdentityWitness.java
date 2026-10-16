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
     * happened to take. */
    public record LogicalKey(String worldId, String domainId, String nodeKey, int role,
                             long packedChunkPos, String targetStatus) {

        /** The unambiguous canonical form this key is carried and compared as: every text field is
         * its own length, a colon and exactly that many characters, and the two numbers are opened
         * by a marker and closed by a semicolon. */
        public String canonical() {
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

    /** Reads a canonical form back, for the round trip a carried comparison key is shown to
     * survive. A form that does not parse is answered with null rather than a guess. */
    public static LogicalKey parse(String text) {
        if (text == null || !text.startsWith("I1")) {
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
