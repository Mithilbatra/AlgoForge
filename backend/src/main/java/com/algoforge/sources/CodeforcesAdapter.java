package com.algoforge.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Official public API: https://codeforces.com/apiHelp (metadata only; statements are not available through it). */
@Component
public class CodeforcesAdapter implements ProblemSourceAdapter {
    static final String API = "https://codeforces.com/api/problemset.problems";
    private static final Pattern PROBLEMSET = Pattern.compile("^/problemset/problem/(\\d+)/([A-Za-z]\\d?)/?$");
    private static final Pattern CONTEST = Pattern.compile("^/contest/(\\d+)/problem/([A-Za-z]\\d?)/?$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;

    public CodeforcesAdapter(ObjectMapper json) { this.json = json; }

    @Override public String code() { return "CODEFORCES"; }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, false, false, true, false, false, true,
                "Official API provides titles, ratings and tags. Statements, tests and judging stay on Codeforces; "
                        + "open the problem there and use AlgoForge for notes and custom-input runs.");
    }

    @Override
    public Optional<String> parseExternalId(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        if (!host.equals("codeforces.com") && !host.endsWith(".codeforces.com")) return Optional.empty();
        for (Pattern p : List.of(PROBLEMSET, CONTEST)) {
            Matcher m = p.matcher(url.getPath());
            if (m.matches()) return Optional.of(m.group(1) + m.group(2).toUpperCase());
        }
        return Optional.empty();
    }

    @Override
    public String canonicalUrl(String externalId) {
        Matcher m = Pattern.compile("^(\\d+)([A-Z]\\d?)$").matcher(externalId);
        if (!m.matches()) throw new IllegalArgumentException("bad Codeforces id " + externalId);
        return "https://codeforces.com/problemset/problem/" + m.group(1) + "/" + m.group(2);
    }

    @Override
    public List<ExternalProblem> fetchCatalogue() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Codeforces API returned HTTP " + res.statusCode());
        return parse(json.readTree(res.body()));
    }

    static List<ExternalProblem> parse(JsonNode root) {
        if (!"OK".equals(root.path("status").asText())) {
            throw new IllegalStateException("Codeforces API error: " + root.path("comment").asText("unknown"));
        }
        List<ExternalProblem> out = new ArrayList<>();
        for (JsonNode p : root.path("result").path("problems")) {
            if (!p.hasNonNull("contestId") || !p.hasNonNull("index") || !p.hasNonNull("name")) continue;
            String index = p.get("index").asText().toUpperCase();
            if (!index.matches("[A-Z]\\d?")) continue;
            List<String> tags = new ArrayList<>();
            p.path("tags").forEach(t -> tags.add(t.asText()));
            Integer rating = p.hasNonNull("rating") ? p.get("rating").asInt() : null;
            out.add(new ExternalProblem(p.get("contestId").asText() + index, p.get("name").asText(), rating, tags));
        }
        return out;
    }
}
