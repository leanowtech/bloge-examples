package com.leanowtech.bloge.gateway.testing.world.draft;

/** Closed lifecycle for a World draft candidate. */
public enum WorldDraftState {
    CAPTURED,
    REDACTION_REQUIRED,
    REVIEW_READY,
    APPROVED,
    REJECTED,
    MATERIALIZED_DRAFT,
    PUBLISHED;

    public boolean mayAdvanceTo(WorldDraftState next) {
        if (next == null) return false;
        return switch (this) {
            case CAPTURED -> next == REDACTION_REQUIRED;
            case REDACTION_REQUIRED -> next == REVIEW_READY || next == REJECTED;
            case REVIEW_READY -> next == APPROVED || next == REJECTED;
            case APPROVED -> next == MATERIALIZED_DRAFT;
            case MATERIALIZED_DRAFT -> next == PUBLISHED;
            case REJECTED, PUBLISHED -> false;
        };
    }
}
