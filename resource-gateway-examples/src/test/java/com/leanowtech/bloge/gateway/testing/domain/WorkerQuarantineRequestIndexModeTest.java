package com.leanowtech.bloge.gateway.testing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerQuarantineRequestIndexModeTest {

    @Test
    void parsesOnlyTheClosedRolloutVocabulary() {
        assertThat(WorkerQuarantineRequestIndexMode.parse(" legacy_read_write "))
                .isEqualTo(WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE);
        assertThat(WorkerQuarantineRequestIndexMode.parse("dual_read_keyed_write"))
                .isEqualTo(WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE);
        assertThat(WorkerQuarantineRequestIndexMode.parse("KEYED_ONLY"))
                .isEqualTo(WorkerQuarantineRequestIndexMode.KEYED_ONLY);

        assertThatThrownBy(() -> WorkerQuarantineRequestIndexMode.parse("dual-write"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LEGACY_READ_WRITE")
                .hasMessageNotContaining("dual-write");
        assertThatThrownBy(() -> WorkerQuarantineRequestIndexMode.parse(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is required");
    }

    @Test
    void exposesTheOneWayCompatibilityMatrix() {
        assertThat(WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE.writesLegacy()).isTrue();
        assertThat(WorkerQuarantineRequestIndexMode.LEGACY_READ_WRITE
                .permitsLiveKeyedRows()).isFalse();
        assertThat(WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE
                .migratesLegacyOnAccess()).isTrue();
        assertThat(WorkerQuarantineRequestIndexMode.KEYED_ONLY
                .permitsLiveLegacyRows()).isFalse();
        assertThat(WorkerQuarantineRequestIndexMode.KEYED_ONLY.writesLegacy()).isFalse();
    }
}
