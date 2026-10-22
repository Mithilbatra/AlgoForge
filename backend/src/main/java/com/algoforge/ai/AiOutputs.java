package com.algoforge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parsing of model replies. Model output is untrusted data: every field is type-checked and length-capped. */
public final class AiOutputs {
    private AiOutputs() {}

    public record Bug(String description, String location) {}

    public record Suggestion(String title, String rationale, String tradeoff, String code) {}

    public record Review(String summary, String approach, String correctnessOpinion, List<Bug> suspectedBugs,
                         String timeComplexity, String spaceComplexity, String complexityExplanation, String fitsLimits,
                         List<Suggestion> suggestions) {}

    public record TestProposal(String category, String rationale, String input, String claimedOutput) {}

    public record CoachReply(String reply, String nextStep) {}

    public static class InvalidOutput extends Exception {
        public InvalidOutput(String message) { super(message); }
    }

    public static Review review(JsonNode n) throws InvalidOutput {
        List<Bug> bugs = new ArrayList<>();
        for (JsonNode b : array(n, "suspected_bugs", 10)) bugs.add(new Bug(str(b, "description", 1000), str(b, "location", 200)));
        List<Suggestion> sugg = new ArrayList<>();
        for (JsonNode s : array(n, "suggestions", 5)) {
            sugg.add(new Suggestion(str(s, "title", 200), str(s, "rationale", 2000), str(s, "tradeoff", 1000), str(s, "code", 20000)));
        }
        return new Review(str(n, "summary", 3000), str(n, "approach", 1000),
                oneOf(n, "correctness_opinion", Set.of("LIKELY_CORRECT", "LIKELY_INCORRECT", "UNSURE")), bugs,
                str(n, "time_complexity", 60), str(n, "space_complexity", 60), str(n, "complexity_explanation", 3000),
                oneOf(n, "fits_limits", Set.of("YES", "NO", "UNSURE")), sugg);
    }

    public static CoachReply coach(JsonNode n) throws InvalidOutput {
        // Strip before capping: capping first could cut off a closing fence and let the code through.
        String reply = stripCode(str(n, "reply", Integer.MAX_VALUE));
        return new CoachReply(reply.length() > 3000 ? reply.substring(0, 3000) : reply, str(n, "next_step", 400));
    }

    // Fences as markdown reads them: three or more ` or ~ at a line start (an info string with backticks is inline code
    // instead), closed by at least as many at a line start, or running to the end of the reply when unclosed.
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})[^`\\n]*\\n(.*?)(?:^ {0,3}\\1|\\z)", Pattern.DOTALL | Pattern.MULTILINE);
    static final int MAX_CODE_LINES = 6;

    /**
     * The coach must not hand out solutions: fenced code longer than a few lines is removed, whatever the model sent.
     * shortcut: only fenced code is checked, not 4-space indented blocks; parse the markdown if models start sending those.
     */
    public static String stripCode(String s) {
        Matcher m = FENCE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            boolean tooLong = m.group(2).strip().lines().count() > MAX_CODE_LINES;
            m.appendReplacement(out, Matcher.quoteReplacement(tooLong ? "_[code removed: Forge Coach shares ideas, not solutions]_" : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static List<TestProposal> tests(JsonNode n) throws InvalidOutput {
        List<TestProposal> out = new ArrayList<>();
        for (JsonNode c : array(n, "cases", 12)) {
            String input = str(c, "input", 20000);
            if (input.isBlank()) continue;
            out.add(new TestProposal(str(c, "category", 80), str(c, "rationale", 500), input.endsWith("\n") ? input : input + "\n",
                    str(c, "claimed_output", 20000)));
        }
        return out;
    }

    private static String str(JsonNode n, String field, int max) throws InvalidOutput {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) throw new InvalidOutput("missing string field " + field);
        String s = v.asText();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String oneOf(JsonNode n, String field, Set<String> allowed) throws InvalidOutput {
        String s = str(n, field, 40);
        if (!allowed.contains(s)) throw new InvalidOutput(field + " has unexpected value " + s);
        return s;
    }

    private static List<JsonNode> array(JsonNode n, String field, int max) throws InvalidOutput {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || !v.isArray()) throw new InvalidOutput("missing array field " + field);
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, v.size()); i++) {
            if (!v.get(i).isObject()) throw new InvalidOutput(field + " items must be objects");
            out.add(v.get(i));
        }
        return out;
    }
}
