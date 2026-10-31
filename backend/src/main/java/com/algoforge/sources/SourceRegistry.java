package com.algoforge.sources;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class SourceRegistry {
    private static final String NO_API = "No official public API for problem data that permits importing it. "
            + "Add problems as links; statements, tests and verdicts stay on the platform.";

    private final Map<String, ProblemSourceAdapter> adapters;

    public SourceRegistry(CodeforcesAdapter codeforces) {
        this.adapters = Stream.of(
                codeforces,
                new LinkOnlyAdapter("LEETCODE", "leetcode.com", "^/problems/([a-z0-9-]+)/?.*$",
                        id -> "https://leetcode.com/problems/" + id + "/", NO_API),
                new LinkOnlyAdapter("CODECHEF", "codechef.com", "^(?:/[A-Z0-9_]+)?/problems/([A-Z0-9_]+)/?$",
                        id -> "https://www.codechef.com/problems/" + id,
                        "CodeChef's developer API has required registered client credentials; its current access terms "
                                + "have not been verified for this integration, so it is link-only."),
                new LinkOnlyAdapter("ATCODER", "atcoder.jp", "^/contests/[a-z0-9_-]+/tasks/([a-z0-9_]+)/?$",
                        id -> "https://atcoder.jp/contests/" + id.replaceFirst("_[a-z0-9]+$", "") + "/tasks/" + id,
                        "AtCoder has no official problem API; community datasets exist but are not integrated."),
                new LinkOnlyAdapter("CSES", "cses.fi", "^/problemset/task/(\\d+)/?$",
                        id -> "https://cses.fi/problemset/task/" + id, NO_API),
                new LinkOnlyAdapter("HACKERRANK", "hackerrank.com", "^/challenges/([a-z0-9-]+)(?:/problem)?/?$",
                        id -> "https://www.hackerrank.com/challenges/" + id + "/problem", NO_API)
        ).collect(Collectors.toUnmodifiableMap(ProblemSourceAdapter::code, Function.identity()));
    }

    public Optional<ProblemSourceAdapter> get(String code) { return Optional.ofNullable(adapters.get(code)); }

    /** Finds the platform a URL belongs to; empty means "some other site" (stored as a MANUAL link). */
    public Optional<Match> match(URI url) {
        for (ProblemSourceAdapter a : adapters.values()) {
            Optional<String> id = a.parseExternalId(url);
            if (id.isPresent()) return Optional.of(new Match(a, id.get(), a.canonicalUrl(id.get())));
        }
        return Optional.empty();
    }

    public record Match(ProblemSourceAdapter adapter, String externalId, String canonicalUrl) {}
}
