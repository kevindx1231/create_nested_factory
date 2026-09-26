package com.createnestedfactory.create_nested_factory.block.entity;

/** Pure pairing decision shared by passage travel and its regression coverage. */
public final class FactoryPassagePairing {
    public enum IntoStatus {
        PRIMARY_INNER,
        DEFAULT_ENTRY,
        UNPAIRED
    }

    private FactoryPassagePairing() {
    }

    public static IntoStatus classifyInto(boolean currentIsBoundOuter, boolean hasPrimaryInner) {
        if (!currentIsBoundOuter) return IntoStatus.UNPAIRED;
        return hasPrimaryInner ? IntoStatus.PRIMARY_INNER : IntoStatus.DEFAULT_ENTRY;
    }
}
