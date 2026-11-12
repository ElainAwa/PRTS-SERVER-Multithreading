/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

/** Builds the one reservation request a controlled effect asks for: the frozen declaration gives
 * the seat, the artefact and the instance, the caller gives the version that declaration authorized,
 * and the terminal payloads of the binding give the effect reference and the row count. No
 * configuration, refusal code or read key is added here. */
public final class EffectRequestFactory {

    private EffectRequestFactory() {
    }

    /** Builds a request from the frozen declaration, the expected version and the three terminals. */
    public static CommitTicket.ReserveRequest request(FrozenDeclarations.Entry declaration,
                                                      long expectedVersion, long writeSetDigest,
                                                      CommitLog.Batch.Kind kind,
                                                      CommitNamespaces.Namespace producer,
                                                      CommitNamespaces.Namespace controller,
                                                      EffectStore.Effect published,
                                                      EffectStore.Effect partial,
                                                      EffectStore.Effect failed) {
        if (declaration == null || declaration.seat() == null || kind == null || producer == null
            || controller == null || published == null || partial == null || failed == null) {
            throw new IllegalArgumentException("a request needs a declaration, a kind, two namespaces "
                + "and three terminals");
        }
        if (!declaration.bindingDeclared() || declaration.targetStatus().isEmpty()) {
            throw new IllegalArgumentException("the declaration does not carry a binding");
        }
        if (!producer.declared() || !controller.declared()) {
            throw new IllegalArgumentException("both namespaces of a request have to be declared");
        }
        if (expectedVersion <= 0L) {
            throw new IllegalArgumentException("a request needs a positive expected version");
        }
        long effectRef = published.effectRef();
        if (effectRef <= 0L || partial.effectRef() != effectRef || failed.effectRef() != effectRef) {
            throw new IllegalArgumentException("the three terminals need one positive reference");
        }
        int rows = EffectStore.normalizedRows(published.rows()).size();
        if (rows <= 0 || EffectStore.normalizedRows(partial.rows()).size() != rows
            || EffectStore.normalizedRows(failed.rows()).size() != rows) {
            throw new IllegalArgumentException("the three terminals need one non-empty row count");
        }
        CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED,
            declaration.instanceId(), declaration.worldEpoch(), declaration.packedChunkPos(),
            declaration.targetStatus(), expectedVersion, effectRef, producer, controller);
        FrozenDeclarations.Seat seat = declaration.seat();
        return new CommitTicket.ReserveRequest(seat.worldId(), seat.domainId(),
            declaration.nodeKey(), kind, seat.planSequence(), seat.position(), expectedVersion,
            writeSetDigest, rows, identity, controller, published, partial, failed);
    }

    /** Builds a request whose terminals are the ones the effect binding already holds. */
    public static CommitTicket.ReserveRequest request(ControlledEffect effect,
                                                      FrozenDeclarations.Entry declaration,
                                                      long expectedVersion, long writeSetDigest,
                                                      CommitLog.Batch.Kind kind,
                                                      CommitNamespaces.Namespace producer,
                                                      CommitNamespaces.Namespace controller) {
        if (effect == null) {
            throw new IllegalArgumentException("a request needs an effect binding");
        }
        return request(declaration, expectedVersion, writeSetDigest, kind, producer, controller,
            effect.published(), effect.partial(), effect.failed());
    }
}
