package com.algoforge.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;

class SourceAdaptersTest {
    final ObjectMapper json = new ObjectMapper();
    final SourceRegistry registry = new SourceRegistry(new CodeforcesAdapter(json));

    SourceRegistry.Match match(String url) { return registry.match(URI.create(url)).orElseThrow(); }

    @Test
    void codeforcesUrlsFromProblemsetAndContestDeduplicateToOneId() {
        var a = match("https://codeforces.com/problemset/problem/1971/a");
        var b = match("https://codeforces.com/contest/1971/problem/A");
        assertThat(a.externalId()).isEqualTo("1971A").isEqualTo(b.externalId());
        assertThat(a.canonicalUrl()).isEqualTo("https://codeforces.com/problemset/problem/1971/A");
    }

    @Test
    void linkOnlyPlatformsParseTheirIds() {
        assertThat(match("https://leetcode.com/problems/two-sum/description/").externalId()).isEqualTo("two-sum");
        assertThat(match("https://www.codechef.com/START100/problems/ABC12").externalId()).isEqualTo("ABC12");
        assertThat(match("https://atcoder.jp/contests/abc300/tasks/abc300_a").canonicalUrl()).isEqualTo("https://atcoder.jp/contests/abc300/tasks/abc300_a");
        assertThat(match("https://cses.fi/problemset/task/1068").externalId()).isEqualTo("1068");
        assertThat(match("https://www.hackerrank.com/challenges/solve-me-first/problem").externalId()).isEqualTo("solve-me-first");
    }

    @Test
    void unknownSitesAndNonProblemPagesDoNotMatch() {
        assertThat(registry.match(URI.create("https://example.com/problems/x"))).isEmpty();
        assertThat(registry.match(URI.create("https://codeforces.com/blog/entry/1"))).isEmpty();
        assertThat(registry.match(URI.create("https://evil-leetcode.com/problems/two-sum/"))).isEmpty();
    }

    @Test
    void capabilitiesAreDeclaredHonestly() {
        assertThat(registry.get("CODEFORCES").orElseThrow().capabilities().metadataSync()).isTrue();
        assertThat(registry.get("CODEFORCES").orElseThrow().capabilities().statementAccess()).isFalse();
        assertThat(registry.get("LEETCODE").orElseThrow().capabilities().metadataSync()).isFalse();
        assertThat(registry.get("LEETCODE").orElseThrow().capabilities().judging()).isFalse();
    }

    @Test
    void parsesCodeforcesApiFixture() throws Exception {
        var root = json.readTree("""
                {"status": "OK", "result": {"problems": [
                  {"contestId": 1971, "index": "A", "name": "My First Sorting Problem", "type": "PROGRAMMING", "rating": 800, "tags": ["implementation", "sortings"]},
                  {"contestId": 1971, "index": "H", "name": "Unrated", "type": "PROGRAMMING", "tags": []},
                  {"index": "B", "name": "No contest id"}], "problemStatistics": []}}""");
        var problems = CodeforcesAdapter.parse(root);
        assertThat(problems).hasSize(2);
        assertThat(problems.getFirst().externalId()).isEqualTo("1971A");
        assertThat(problems.getFirst().rating()).isEqualTo(800);
        assertThat(problems.get(1).rating()).isNull();
    }

    @Test
    void codeforcesApiErrorsSurface() throws Exception {
        var root = json.readTree("{\"status\": \"FAILED\", \"comment\": \"Call limit exceeded\"}");
        assertThatThrownBy(() -> CodeforcesAdapter.parse(root)).hasMessageContaining("Call limit exceeded");
    }

    @Test
    void ratingMapsToDifficulty() {
        assertThat(SourceSyncService.difficultyFor(800)).isEqualTo("EASY");
        assertThat(SourceSyncService.difficultyFor(1500)).isEqualTo("MEDIUM");
        assertThat(SourceSyncService.difficultyFor(2400)).isEqualTo("HARD");
        assertThat(SourceSyncService.difficultyFor(null)).isNull();
    }
}
