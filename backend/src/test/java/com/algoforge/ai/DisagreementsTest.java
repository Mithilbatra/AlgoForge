package com.algoforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.ai.AiOutputs.Review;
import com.algoforge.ai.Disagreements.Evidence;
import com.algoforge.ai.Disagreements.ProviderReview;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisagreementsTest {
    static ProviderReview pr(String provider, String opinion, String time, String space) {
        return new ProviderReview(provider, new Review("s", "a", opinion, List.of(), time, space, "e", "UNSURE", List.of()));
    }

    static Evidence evidence(boolean accepted, int failed, Double exponent) {
        return new Evidence(accepted, 8, failed, 45, 0, "O(n²)", "LOW", "O(n)", exponent);
    }

    @Test
    void agreementWithEvidenceProducesNothing() {
        var items = Disagreements.resolve(List.of(pr("alpha", "LIKELY_CORRECT", "O(n)", "O(n)"), pr("beta", "LIKELY_CORRECT", "O(n)", "O(n)")),
                evidence(true, 0, 1.02));
        assertThat(items).isEmpty();
    }

    @Test
    void executionFailuresContradictACorrectnessClaimEvenWhenBothProvidersAgree() {
        var items = Disagreements.resolve(List.of(pr("alpha", "LIKELY_CORRECT", "O(n)", "O(n)"), pr("beta", "LIKELY_CORRECT", "O(n)", "O(n)")),
                evidence(false, 2, null));
        assertThat(items).hasSize(2).allMatch(i -> i.status().equals("CONTRADICTED_BY_EVIDENCE"));
    }

    @Test
    void benchmarkResolvesComplexityDisagreementInsteadOfVoting() {
        var items = Disagreements.resolve(List.of(pr("alpha", "LIKELY_CORRECT", "O(n²)", "O(n)"), pr("beta", "LIKELY_CORRECT", "O(n log n)", "O(n)")),
                evidence(true, 0, 1.08));
        var time = items.stream().filter(i -> i.topic().equals("time complexity") && i.status().equals("RESOLVED")).findFirst().orElseThrow();
        assertThat(time.conclusion()).contains("beta");
        assertThat(items).anyMatch(i -> i.status().equals("CONTRADICTED_BY_EVIDENCE") && i.claims().containsKey("alpha"));
    }

    @Test
    void withoutEvidenceADisagreementStaysUnresolved() {
        var items = Disagreements.resolve(List.of(pr("alpha", "LIKELY_INCORRECT", "O(n²)", "O(1)"), pr("beta", "LIKELY_CORRECT", "O(n log n)", "O(n)")),
                new Evidence(false, 0, 0, 0, 0, "O(n)", "LOW", "O(n²)", null));
        assertThat(items).extracting(Disagreements.Item::status).containsOnly("UNRESOLVED");
        assertThat(items).extracting(Disagreements.Item::topic).containsExactly("correctness", "time complexity", "space complexity");
    }
}
