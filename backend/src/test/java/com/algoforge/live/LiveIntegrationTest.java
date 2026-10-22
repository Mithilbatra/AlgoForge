package com.algoforge.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.algoforge.AppProperties;
import com.algoforge.ai.AiOutputs;
import com.algoforge.ai.AiProvider;
import com.algoforge.ai.ClaudeProvider;
import com.algoforge.ai.GeminiProvider;
import com.algoforge.ai.Prompts;
import com.algoforge.sources.CodeforcesAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Opt-in tests against real external services. They cost money or hit public rate limits, so each runs only when
 * its credential (or LIVE_TESTS=1 for Codeforces) is present in the environment; otherwise it is skipped.
 */
class LiveIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PROMPT = "## Problem: Sum\nPrint a + b.\n\n## Submission (python)\n```\na, b = map(int, input().split())\nprint(a + b)\n```\n"
            + "## Execution evidence (authoritative)\n- Verdict: ACCEPTED, passed 5/5 judge tests\n";

    static AppProperties props() {
        var ai = new AppProperties.Ai(System.getenv("ANTHROPIC_API_KEY"), System.getenv("GEMINI_API_KEY"), false, 120, 5, 1);
        return new AppProperties(null, null, null, ai, null);
    }

    void reviewWith(AiProvider p, String model) throws Exception {
        AiProvider.Completion c = p.complete(model, Prompts.REVIEW_SYSTEM, PROMPT, Prompts.REVIEW_SCHEMA, 4000);
        AiOutputs.Review r = AiOutputs.review(JSON.readTree(c.text()));
        assertThat(r.timeComplexity()).startsWith("O(");
        assertThat(c.outputTokens()).isPositive();
    }

    @Test
    void claudeReturnsSchemaValidReview() throws Exception {
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set");
        reviewWith(new ClaudeProvider(props(), JSON), System.getenv().getOrDefault("CLAUDE_MODEL", "claude-opus-5-5"));
    }

    @Test
    void geminiReturnsSchemaValidReview() throws Exception {
        assumeTrue(System.getenv("GEMINI_API_KEY") != null, "GEMINI_API_KEY not set");
        reviewWith(new GeminiProvider(props(), JSON), System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.8-flash"));
    }

    @Test
    void codeforcesCatalogueSyncs() throws Exception {
        assumeTrue("1".equals(System.getenv("LIVE_TESTS")), "LIVE_TESTS=1 not set");
        var problems = new CodeforcesAdapter(JSON).fetchCatalogue();
        assertThat(problems).hasSizeGreaterThan(5000);
        assertThat(problems).anyMatch(p -> p.rating() != null && !p.tags().isEmpty());
    }
}
