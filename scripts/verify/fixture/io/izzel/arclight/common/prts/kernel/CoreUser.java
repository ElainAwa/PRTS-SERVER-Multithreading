package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.domain.DomainType;

/** Fixture only: the deliberate core -> domain edge the purity rule must refuse. */
public final class CoreUser {

    public int use() {
        return DomainType.domainShape();
    }
}
