package com.algoforge.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.learning.LearningService.DimensionEstimate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningEstimatesTest {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-01T12:00:00Z");

    @Test
    void estimateIsBetaPosteriorMean() {
        var e = LearningService.estimate("IMPLEMENTATION", 3, 1, NOW, NOW);
        assertThat(e.estimate()).isEqualTo(4.0 / 6.0);
        assertThat(e.evidence()).isEqualTo(4.0);
    }

    @Test
    void oldEvidenceDecaysTowardHalf() {
        var fresh = LearningService.estimate("IMPLEMENTATION", 6, 0, NOW, NOW);
        var stale = LearningService.estimate("IMPLEMENTATION", 6, 0, NOW.minusDays(60), NOW);
        assertThat(stale.estimate()).isLessThan(fresh.estimate()).isGreaterThan(0.5);
        assertThat(stale.evidence()).isEqualTo(1.5);
    }

    @Test
    void oneAcceptedSubmissionIsNeverMastery() {
        var dims = List.of(new DimensionEstimate("IMPLEMENTATION", 2.0 / 3, 1, NOW));
        assertThat(LearningService.status(dims)).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void strongRequiresTransferEvidence() {
        var noTransfer = List.of(new DimensionEstimate("IMPLEMENTATION", 0.9, 6, NOW));
        assertThat(LearningService.status(noTransfer)).isEqualTo("DEVELOPING");
        var withTransfer = List.of(new DimensionEstimate("IMPLEMENTATION", 0.9, 6, NOW), new DimensionEstimate("TRANSFER", 0.8, 3, NOW));
        assertThat(LearningService.status(withTransfer)).isEqualTo("STRONG");
        var weak = List.of(new DimensionEstimate("IMPLEMENTATION", 0.2, 5, NOW));
        assertThat(LearningService.status(weak)).isEqualTo("WEAK");
    }
}
