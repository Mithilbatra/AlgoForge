package com.algoforge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiOutputsTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String VALID = """
            {"summary": "ok", "approach": "binary search", "correctness_opinion": "LIKELY_CORRECT", "suspected_bugs": [],
             "time_complexity": "O(n log n)", "space_complexity": "O(n)", "complexity_explanation": "one bisect per query",
             "fits_limits": "YES", "suggestions": [{"title": "t", "rationale": "r", "tradeoff": "none", "code": "x"}]}""";

    @Test
    void parsesAValidReview() throws Exception {
        var r = AiOutputs.review(M.readTree(VALID));
        assertThat(r.correctnessOpinion()).isEqualTo("LIKELY_CORRECT");
        assertThat(r.suggestions()).hasSize(1);
    }

    @Test
    void rejectsUnknownEnumValues() throws Exception {
        var node = M.readTree(VALID.replace("LIKELY_CORRECT", "DEFINITELY_CORRECT"));
        assertThatThrownBy(() -> AiOutputs.review(node)).isInstanceOf(AiOutputs.InvalidOutput.class);
    }

    @Test
    void rejectsMissingOrMistypedFields() throws Exception {
        assertThatThrownBy(() -> AiOutputs.review(M.readTree("{\"summary\": 3}"))).isInstanceOf(AiOutputs.InvalidOutput.class);
        assertThatThrownBy(() -> AiOutputs.review(M.readTree("[]"))).isInstanceOf(AiOutputs.InvalidOutput.class);
        assertThatThrownBy(() -> AiOutputs.tests(M.readTree("{\"cases\": [\"not an object\"]}"))).isInstanceOf(AiOutputs.InvalidOutput.class);
    }

    @Test
    void capsLengthsAndCounts() throws Exception {
        String longSummary = "x".repeat(10_000);
        var r = AiOutputs.review(M.readTree(VALID.replace("\"summary\": \"ok\"", "\"summary\": \"" + longSummary + "\"")));
        assertThat(r.summary()).hasSize(3000);
        StringBuilder cases = new StringBuilder("{\"cases\": [");
        for (int i = 0; i < 30; i++) cases.append(i == 0 ? "" : ",").append("{\"category\":\"c\",\"rationale\":\"r\",\"input\":\"1 2\",\"claimed_output\":\"3\"}");
        var tests = AiOutputs.tests(M.readTree(cases.append("]}").toString()));
        assertThat(tests).hasSize(12);
        assertThat(tests.getFirst().input()).isEqualTo("1 2\n");
    }

    @Test
    void coachRepliesNeverCarryLongCode() throws Exception {
        String longCode = "```python\n" + "x = 1\n".repeat(12) + "```";
        var r = AiOutputs.coach(M.readTree(M.writeValueAsString(java.util.Map.of("reply", "Try this:\n" + longCode + "\nand ```a = b``` inline", "next_step", "go"))));
        assertThat(r.reply()).contains("code removed").doesNotContain("x = 1\nx = 1");
        String shortCode = "```\nlo, hi = 0, n\n```";
        assertThat(AiOutputs.stripCode(shortCode)).isEqualTo(shortCode);
    }

    @Test
    void coachStripsUnclosedAndOverLongCodeBlocks() throws Exception {
        String unclosed = "Here:\n```python\n" + "x = 1\n".repeat(12);
        assertThat(AiOutputs.stripCode(unclosed)).contains("code removed").doesNotContain("x = 1");
        // The closing fence sits past the 3000-character cap; the code must still be removed.
        String longReply = "Here:\n```python\n" + "total = total + value  # step\n".repeat(120) + "```";
        var r = AiOutputs.coach(M.readTree(M.writeValueAsString(java.util.Map.of("reply", longReply, "next_step", "go"))));
        assertThat(r.reply()).contains("code removed").doesNotContain("total = total");
        assertThat(AiOutputs.stripCode("Try:\n~~~\n" + "x = 1\n".repeat(12) + "~~~")).contains("code removed").doesNotContain("x = 1");
        assertThat(AiOutputs.stripCode("Try:\n````python\n" + "x = 1\n".repeat(12) + "````")).contains("code removed").doesNotContain("x = 1");
        // Triple backticks inside a sentence are inline code, not a fence: the prose after them stays.
        String prose = "Use ```lo``` as the left end.\n" + "Then move it right while the condition holds.\n".repeat(8);
        assertThat(AiOutputs.stripCode(prose)).isEqualTo(prose);
    }

    @Test
    void parsesTwinsAndRejectsIncompleteOnes() throws Exception {
        var ok = new java.util.HashMap<String, Object>(java.util.Map.of("title", " Lantern Count ", "statement_md", "s", "constraints_md", "1 ≤ n ≤ 10",
                "reference_py", "print(1)", "brute_py", "print(1)", "generator_py", "print(1)", "samples", java.util.List.of("1", " "),
                "hints", java.util.List.of("a", "b", "c", "d"), "editorial_md", "e", "max_n", 5_000_000));
        ok.put("validator_py", "print(1)");
        var t = AiOutputs.twin(M.valueToTree(ok));
        assertThat(t.title()).isEqualTo("Lantern Count");
        assertThat(t.samples()).containsExactly("1\n");  // blank samples dropped, newline added
        assertThat(t.hints()).hasSize(3);
        assertThat(t.maxN()).isEqualTo(200_000);
        for (var bad : java.util.List.<java.util.Map<String, Object>>of(java.util.Map.of("brute_py", " "), java.util.Map.of("validator_py", " "), java.util.Map.of("max_n", 0),
                java.util.Map.of("samples", "1 2"), java.util.Map.of("title", ""))) {
            var node = new java.util.HashMap<>(ok);
            node.putAll(bad);
            assertThatThrownBy(() -> AiOutputs.twin(M.valueToTree(node))).isInstanceOf(AiOutputs.InvalidOutput.class);
        }
    }
}
