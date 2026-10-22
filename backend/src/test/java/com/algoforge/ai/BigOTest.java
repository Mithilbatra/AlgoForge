package com.algoforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BigOTest {
    static BigO.Growth g(String s) { return BigO.parse(s).orElseThrow(() -> new AssertionError("unparsed: " + s)); }

    @Test
    void parsesCommonForms() {
        assertThat(g("O(n log n)")).isEqualTo(new BigO.Growth(1, 1, false));
        assertThat(g("O(N * log(N))")).isEqualTo(new BigO.Growth(1, 1, false));
        assertThat(g("O(n²)")).isEqualTo(new BigO.Growth(2, 0, false));
        assertThat(g("O(n^2)")).isEqualTo(new BigO.Growth(2, 0, false));
        assertThat(g("O(1)")).isEqualTo(new BigO.Growth(0, 0, false));
        assertThat(g("O(log n)")).isEqualTo(new BigO.Growth(0, 1, false));
        assertThat(g("O(V + E)")).isEqualTo(new BigO.Growth(1, 0, false));
        assertThat(g("O(r·c)")).isEqualTo(new BigO.Growth(2, 0, false));
        assertThat(g("O(n log^2 n)")).isEqualTo(new BigO.Growth(1, 2, false));
        assertThat(g("O(2^n)").exponential()).isTrue();
    }

    @Test
    void rejectsNonBigOText() {
        assertThat(BigO.parse("linear time")).isEmpty();
        assertThat(BigO.parse(null)).isEmpty();
        assertThat(BigO.parse("O(n + whatever)")).isEmpty();
    }
}
