package com.algoforge.eval;

import com.algoforge.judge.Language;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, explainable heuristics over source text: loop nesting (with binary-search, constant, amortised
 * and union-find loops recognised), recursion and memoisation, sorting and data-structure costs, plus language
 * pitfalls (slow I/O, flushing, overflow, recursion depth). Results are estimates and are labelled as such.
 */
public final class StaticAnalyzer {
    private StaticAnalyzer() {}

    public record Finding(String severity, String category, String message) {}

    public record Result(String timeComplexity, String spaceComplexity, String explanation, String confidence,
                         int linearDepth, int logPower, boolean exponential, List<Finding> findings,
                         int readabilityScore, List<String> readabilityNotes, int languageScore) {}

    record Loop(int start, int end, Kind kind, String header) {}

    enum Kind { LINEAR, LOG, CONSTANT, AMORTISED }

    private static final Pattern MID = Pattern.compile("\\bmid\\w*\\s*=|\\(\\s*\\w+\\s*\\+\\s*\\w+\\s*\\)\\s*(/\\s*2|>>\\s*1|//\\s*2)|\\w+\\s*\\+\\s*\\(\\s*\\w+\\s*-\\s*\\w+\\s*\\)\\s*(/|>>|//)");
    private static final Pattern HALVING = Pattern.compile("(/=|//=)\\s*2\\b|>>=\\s*1\\b|\\*=\\s*2\\b|<<=\\s*1\\b");
    private static final Pattern CONST_BOUND = Pattern.compile("<=?\\s*([0-9]{1,2})\\s*;|range\\(\\s*([0-9]{1,2})\\s*\\)|in\\s*[(\\[]\\s*[(\\[]?-?\\d");
    private static final Pattern DSU_FIND = Pattern.compile("while\\s*\\(?\\s*\\w+\\s*\\[\\s*(\\w+)\\s*\\]\\s*!=\\s*\\1\\b");
    private static final Pattern QUEUE_DRAIN = Pattern.compile("while\\s*\\(\\s*!\\s*\\w+\\s*\\.\\s*(empty|isEmpty)\\s*\\(\\s*\\)\\s*\\)|while\\s+(len\\()?\\w+\\)?\\s*:");
    private static final Pattern SORT = Pattern.compile("\\bsort\\s*\\(|Arrays\\.sort|Collections\\.sort|\\.sort\\s*\\(|\\bsorted\\s*\\(");
    private static final Pattern LOG_DS = Pattern.compile("priority_queue|PriorityQueue|heapq|TreeMap|TreeSet|\\bstd::(map|set)\\b|\\b(map|set|multiset)\\s*<|bisect|lower_bound|upper_bound|binarySearch");

    public static Result analyze(String code, Language lang, String constraints) {
        String clean = lang == Language.PYTHON ? stripPython(code) : stripCLike(code);
        List<Loop> loops = lang == Language.PYTHON ? pythonLoops(clean) : cLikeLoops(clean);
        List<String> notes = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        // Dominant term: the loop with the most linear ancestors (log loops add a log factor instead).
        int bestLinear = 0, bestLog = 0;
        for (Loop l : loops) {
            int lin = 0, lg = 0;
            for (Loop o : loops) {
                if (o.start() <= l.start() && o.end() >= l.end()) {
                    if (o.kind() == Kind.LINEAR) lin++;
                    else if (o.kind() == Kind.LOG) lg++;
                }
            }
            if (lin > bestLinear || lin == bestLinear && lg > bestLog) { bestLinear = lin; bestLog = lg; }
        }
        long logLoops = loops.stream().filter(l -> l.kind() == Kind.LOG).count();
        long amortised = loops.stream().filter(l -> l.kind() == Kind.AMORTISED).count();
        if (!loops.isEmpty()) {
            notes.add(loops.size() + " loop(s); deepest nesting of input-sized loops: " + bestLinear
                    + (logLoops > 0 ? "; " + logLoops + " halving/binary-search loop(s) counted as log factors" : "")
                    + (amortised > 0 ? "; " + amortised + " inner loop(s) treated as amortised (two-pointer, BFS or union-find pattern)" : ""));
        }

        Matcher sort = SORT.matcher(clean);
        if (sort.find()) {
            int depth = linearDepthAt(loops, sort.start());
            notes.add("sorting at loop depth " + depth + " costs O(n log n) per call");
            if (depth + 1 > bestLinear || depth + 1 == bestLinear && bestLog < 1) { bestLinear = depth + 1; bestLog = Math.max(bestLog, 1); }
        }
        if (LOG_DS.matcher(clean).find() && bestLinear >= 1 && bestLog == 0) {
            bestLog = 1;
            notes.add("ordered/heap structures or bisect add an O(log n) factor per operation");
        }

        Recursion rec = recursion(clean, lang);
        boolean exponential = false;
        if (rec.name() != null) {
            if (rec.memoised()) {
                notes.add("recursive function " + rec.name() + " is memoised: cost is about the number of distinct states");
                bestLinear = Math.max(bestLinear, 1);
            } else if (rec.selfCalls() >= 2) {
                exponential = true;
                notes.add("recursive function " + rec.name() + " calls itself " + rec.selfCalls() + " times without memoisation: exponential in the depth");
                findings.add(new Finding("WARN", "performance", "Recursion in " + rec.name() + " branches without memoisation; cache results (or tabulate) to avoid exponential time."));
            } else {
                notes.add("recursive function " + rec.name() + " (single self-call): depth can grow with the input");
                bestLinear = Math.max(bestLinear, 1);
            }
            boolean pyLimit = lang == Language.PYTHON && code.contains("setrecursionlimit");
            if (lang == Language.PYTHON && !pyLimit) {
                findings.add(new Finding("WARN", "robustness", "Python's default recursion limit is 1000; deep inputs raise RecursionError. Use an explicit stack or sys.setrecursionlimit with a thread stack size."));
            } else if (lang != Language.PYTHON) {
                findings.add(new Finding("INFO", "robustness", "Recursion depth proportional to n can overflow the call stack on degenerate inputs (long chains, sorted data)."));
            }
        }

        if (loops.isEmpty() && rec.name() == null && !sort.reset().find()) notes.add("no explicit loops; linear-time library calls over the input assumed");
        String time = exponential ? "O(2^n)" : bigO(Math.max(bestLinear, 1), bestLog);
        String space = space(clean, lang, rec.name() != null);
        boolean simple = rec.name() == null && amortised == 0 && loops.size() <= 4;
        String confidence = simple ? "MEDIUM" : "LOW";

        int languageScore = 100;
        languageScore -= languageFindings(code, clean, lang, loops, findings);
        overflow(code, lang, constraints, findings);

        List<String> readNotes = new ArrayList<>();
        int readability = readability(code, clean, lang, readNotes);
        return new Result(time, space, String.join("; ", notes), confidence, Math.max(bestLinear, 1), bestLog, exponential,
                findings, readability, readNotes, Math.max(0, languageScore));
    }

    static String bigO(int linear, int log) {
        String n = switch (linear) {
            case 0 -> "";
            case 1 -> "n";
            case 2 -> "n²";
            case 3 -> "n³";
            default -> "n^" + linear;
        };
        String lg = log == 0 ? "" : log == 1 ? "log n" : "log^" + log + " n";
        String body = (n + " " + lg).trim();
        return "O(" + (body.isEmpty() ? "1" : body) + ")";
    }

    private static int linearDepthAt(List<Loop> loops, int pos) {
        return (int) loops.stream().filter(l -> l.kind() == Kind.LINEAR && l.start() <= pos && l.end() > pos).count();
    }

    // ---- loop discovery -----------------------------------------------------------------------------------------

    private static final Pattern LOOP_KW = Pattern.compile("\\b(for|while)\\s*\\(|\\bdo\\s*\\{");

    static List<Loop> cLikeLoops(String s) {
        List<Loop> loops = new ArrayList<>();
        Matcher m = LOOP_KW.matcher(s);
        while (m.find()) {
            int start = m.start();
            if (m.group().startsWith("do")) {
                int open = s.indexOf('{', start);
                int end = matching(s, open, '{', '}');
                int whileEnd = s.indexOf(';', end);
                loops.add(new Loop(start, whileEnd < 0 ? end : whileEnd, classify(s.substring(start, Math.max(end, start)), s.substring(end, whileEnd < 0 ? end : whileEnd), loops, start), s.substring(end, whileEnd < 0 ? end : whileEnd)));
                continue;
            }
            int open = s.indexOf('(', m.start());
            int close = matching(s, open, '(', ')');
            if (close < 0) continue;
            int end = statementEnd(s, close + 1);
            String header = s.substring(start, close + 1);
            String body = s.substring(close + 1, Math.min(end, s.length()));
            if (header.startsWith("while") && body.strip().startsWith(";")) continue;  // the tail of do { } while (...);
            loops.add(new Loop(start, end, classify(header, body, loops, start), header));
        }
        return loops;
    }

    private static Kind classify(String header, String body, List<Loop> outer, int start) {
        if (DSU_FIND.matcher(header).find()) return Kind.AMORTISED;
        if (MID.matcher(body).find() || MID.matcher(header).find() || HALVING.matcher(header).find()) return Kind.LOG;
        if (HALVING.matcher(body).find() && header.startsWith("while") && !body.contains("++")) return Kind.LOG;
        if (CONST_BOUND.matcher(header).find()) return Kind.CONSTANT;
        boolean nested = outer.stream().anyMatch(o -> o.start() < start && o.end() > start);
        if (nested && header.matches("(?s)for\\s*\\(.*:.*\\)") && outer.stream().anyMatch(o -> o.start() < start && o.end() > start && QUEUE_DRAIN.matcher(o.header()).find())) {
            return Kind.AMORTISED;  // adjacency scan inside a queue-drain (BFS) loop: O(V + E) overall
        }
        if (nested && header.startsWith("while") && (body.contains("++") || body.contains("+= 1") || body.contains("-= 1") || body.contains("--"))
                && outer.stream().anyMatch(o -> o.start() < start && o.end() > start && o.header().startsWith("for"))) {
            return Kind.AMORTISED;  // two-pointer: inner pointer only moves forward
        }
        return Kind.LINEAR;
    }

    private static int statementEnd(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return s.length();
        if (s.charAt(i) == '{') {
            int close = matching(s, i, '{', '}');
            return close < 0 ? s.length() : close + 1;
        }
        Matcher kw = Pattern.compile("\\G(for|while|if)\\s*\\(").matcher(s);
        if (kw.find(i)) {
            int close = matching(s, s.indexOf('(', i), '(', ')');
            return close < 0 ? s.length() : statementEnd(s, close + 1);
        }
        int semi = s.indexOf(';', i);
        return semi < 0 ? s.length() : semi + 1;
    }

    private static int matching(String s, int open, char o, char c) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == o) depth++;
            else if (s.charAt(i) == c && --depth == 0) return i;
        }
        return -1;
    }

    static List<Loop> pythonLoops(String s) {
        List<Loop> loops = new ArrayList<>();
        String[] lines = s.split("\n", -1);
        int[] offsets = new int[lines.length + 1];
        for (int i = 0; i < lines.length; i++) offsets[i + 1] = offsets[i] + lines[i].length() + 1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.strip();
            int indent = line.length() - line.stripLeading().length();
            if ((t.startsWith("for ") || t.startsWith("while ")) && t.endsWith(":")) {
                int j = i + 1;
                while (j < lines.length && (lines[j].isBlank() || lines[j].length() - lines[j].stripLeading().length() > indent)) j++;
                String body = s.substring(offsets[i + 1] > s.length() ? s.length() : offsets[i + 1], Math.min(offsets[j], s.length()));
                loops.add(new Loop(offsets[i], Math.min(offsets[j], s.length()), classify(t, body, loops, offsets[i]), t));
            }
            // comprehension / generator loops: "... for x in ..." inside one line
            Matcher m = Pattern.compile("\\sfor\\s+[\\w, ()]+\\s+in\\s").matcher(t.startsWith("for ") ? t.substring(3) : t);
            while (m.find()) {
                String tail = t.substring(Math.min(t.length(), m.end() + (t.startsWith("for ") ? 3 : 0)));
                Kind k = CONST_BOUND.matcher("in " + tail).find() || tail.startsWith("range(2") || tail.startsWith("range(4") ? Kind.CONSTANT : Kind.LINEAR;
                loops.add(new Loop(offsets[i] + indent, offsets[i] + line.length(), k, "comprehension"));
            }
        }
        return loops;
    }

    // ---- recursion ---------------------------------------------------------------------------------------------

    record Recursion(String name, int selfCalls, boolean memoised) {}

    private static final Set<String> NOT_FUNCTIONS = Set.of("if", "for", "while", "switch", "catch", "return", "sizeof", "main", "synchronized");

    static Recursion recursion(String s, Language lang) {
        if (lang == Language.PYTHON) {
            Matcher m = Pattern.compile("(?m)^([ \\t]*)def\\s+(\\w+)\\s*\\(").matcher(s);
            while (m.find()) {
                int indent = m.group(1).length();
                String name = m.group(2);
                int bodyStart = s.indexOf('\n', m.end());
                if (bodyStart < 0) continue;
                int end = bodyStart;
                for (String line : s.substring(bodyStart + 1).split("\n", -1)) {
                    if (!line.isBlank() && line.length() - line.stripLeading().length() <= indent) break;
                    end += line.length() + 1;
                }
                String body = s.substring(bodyStart, Math.min(end, s.length()));
                int calls = count(body, Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\("));
                if (calls > 0) {
                    String before = s.substring(Math.max(0, m.start() - 80), m.start());
                    boolean memo = before.contains("cache") || body.matches("(?s).*\\b(memo|dp|cache)\\w*\\s*\\[.*") || body.contains(" in memo");
                    return new Recursion(name, calls, memo);
                }
            }
            return new Recursion(null, 0, false);
        }
        Matcher m = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\([^;{}()]*\\)\\s*(const\\s*)?(throws\\s+[\\w., ]+)?\\s*\\{").matcher(s);
        while (m.find()) {
            String name = m.group(1);
            if (NOT_FUNCTIONS.contains(name)) continue;
            int open = s.indexOf('{', m.end() - 1);
            int close = matching(s, open, '{', '}');
            if (close < 0) continue;
            String body = s.substring(open, close);
            int calls = count(body, Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\("));
            if (calls > 0) {
                boolean memo = Pattern.compile("\\b(memo|dp|cache|seen|vis)\\w*\\s*[\\[.(]").matcher(body).find()
                        && Pattern.compile("!=\\s*-1|>=\\s*0|containsKey|\\.count\\(|\\.find\\(|computed").matcher(body).find();
                return new Recursion(name, calls, memo);
            }
        }
        return new Recursion(null, 0, false);
    }

    private static int count(String s, Pattern p) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    // ---- space -------------------------------------------------------------------------------------------------

    static String space(String s, Language lang, boolean recursive) {
        boolean twoD = Pattern.compile("new\\s+\\w+\\s*\\[[^\\]]+\\]\\s*\\[[^\\]]+\\]|vector\\s*<\\s*vector|\\[\\s*\\[[^\\]]*\\]\\s*\\*\\s*\\w+\\s+for\\b|\\[\\[[^\\]]*\\]\\s*\\*\\s*\\w+\\s+for\\b").matcher(s).find();
        boolean oneD = Pattern.compile("new\\s+\\w+\\s*\\[|vector\\s*<|ArrayList|HashMap|HashSet|TreeMap|ArrayDeque|unordered_|\\bmap\\s*<|\\bset\\s*<|\\[0\\]\\s*\\*|\\[\\s*-?\\d+\\s*\\]\\s*\\*|list\\(|\\.split\\(|dict\\(|set\\(|defaultdict|Counter|deque\\(|\\[\\s*\\w[^\\]]*\\s+for\\s").matcher(s).find();
        String base = twoD ? "O(n·m)" : oneD ? "O(n)" : "O(1)";
        return recursive && base.equals("O(1)") ? "O(depth) call stack" : base + (recursive ? " + O(depth) call stack" : "");
    }

    // ---- language-specific quality --------------------------------------------------------------------------------

    private static int languageFindings(String raw, String clean, Language lang, List<Loop> loops, List<Finding> out) {
        int penalty = 0;
        switch (lang) {
            case JAVA -> {
                if (clean.contains("new Scanner(System.in)")) {
                    out.add(new Finding("WARN", "language", "java.util.Scanner is slow for 10^5+ tokens; read with BufferedReader + StringTokenizer or a byte buffer."));
                    penalty += 30;
                }
                if (insideLoop(clean, loops, Pattern.compile("System\\.out\\.print(ln)?\\s*\\("))) {
                    out.add(new Finding("WARN", "language", "System.out.println inside a loop is slow; collect output in a StringBuilder or use a PrintWriter."));
                    penalty += 20;
                }
            }
            case CPP -> {
                if (Pattern.compile("\\bcin\\s*>>").matcher(clean).find() && !raw.contains("sync_with_stdio")) {
                    out.add(new Finding("WARN", "language", "cin without ios::sync_with_stdio(false); cin.tie(nullptr); is slow on large inputs."));
                    penalty += 20;
                }
                if (insideLoop(clean, loops, Pattern.compile("\\bendl\\b"))) {
                    out.add(new Finding("WARN", "language", "std::endl flushes the stream on every line; write '\\n' instead inside loops."));
                    penalty += 15;
                }
            }
            case PYTHON -> {
                if (insideLoop(clean, loops, Pattern.compile("\\binput\\s*\\("))) {
                    out.add(new Finding("WARN", "language", "input() per line is slow for large inputs; read everything once with sys.stdin.buffer.read().split()."));
                    penalty += 25;
                }
                if (insideLoop(clean, loops, Pattern.compile("\\bprint\\s*\\("))) {
                    out.add(new Finding("WARN", "language", "print() inside a loop is slow; collect lines and print '\\n'.join(...) once."));
                    penalty += 15;
                }
            }
        }
        return penalty;
    }

    private static boolean insideLoop(String s, List<Loop> loops, Pattern p) {
        Matcher m = p.matcher(s);
        while (m.find()) {
            int pos = m.start();
            if (loops.stream().anyMatch(l -> l.kind() != Kind.CONSTANT && l.start() <= pos && l.end() > pos)) return true;
        }
        return false;
    }

    private static void overflow(String raw, Language lang, String constraints, List<Finding> out) {
        if (lang == Language.PYTHON || constraints == null) return;
        boolean big = Pattern.compile("10\\^(9|1[0-8])|2·10\\^9|5·10\\^9").matcher(constraints).find();
        boolean wide = raw.contains("long long") || raw.contains("int64") || Pattern.compile("\\blong\\b").matcher(raw).find() || raw.contains("#define int");
        if (big && !wide) {
            out.add(new Finding("WARN", "robustness", "Constraints reach 10^9 or more but the code uses no 64-bit integers; sums, products or counts may overflow 32-bit int."));
        }
    }

    // ---- readability ---------------------------------------------------------------------------------------------

    private static int readability(String raw, String clean, Language lang, List<String> notes) {
        int score = 100;
        String[] lines = raw.split("\n");
        long longLines = java.util.Arrays.stream(lines).filter(l -> l.length() > 120).count();
        if (longLines > 0) { score -= (int) Math.min(15, longLines * 3); notes.add(longLines + " line(s) longer than 120 characters"); }
        int defines = count(raw, Pattern.compile("(?m)^\\s*#define\\b"));
        if (defines > 4) { score -= 10; notes.add(defines + " #define macros obscure the code"); }
        int maxDepth = 0, depth = 0;
        if (lang == Language.PYTHON) {
            for (String l : lines) if (!l.isBlank()) maxDepth = Math.max(maxDepth, (l.length() - l.stripLeading().length()) / 4);
        } else {
            for (char c : clean.toCharArray()) { if (c == '{') maxDepth = Math.max(maxDepth, ++depth); else if (c == '}') depth--; }
        }
        if (maxDepth > 5) { score -= 15; notes.add("nesting depth " + maxDepth + "; extract helpers to flatten it"); }
        int functions = count(clean, lang == Language.PYTHON ? Pattern.compile("(?m)^\\s*def\\s") : Pattern.compile("\\b\\w+\\s*\\([^;{}()]*\\)\\s*\\{"));
        long codeLines = java.util.Arrays.stream(lines).filter(l -> !l.isBlank()).count();
        if (codeLines > 60 && functions <= 1) { score -= 15; notes.add(codeLines + " lines in a single function; split into named steps"); }
        Set<String> oneLetter = new LinkedHashSet<>();
        Matcher decl = Pattern.compile("\\b(?:int|long|double|auto|var|String|bool|boolean|char)\\s+([a-zA-Z])\\b").matcher(clean);
        while (decl.find()) if (!"ijknmxyabqtsluvrcdepw".contains(decl.group(1))) oneLetter.add(decl.group(1));
        if (oneLetter.size() > 4) { score -= 10; notes.add("many single-letter names (" + String.join(", ", oneLetter) + ")"); }
        if (notes.isEmpty()) notes.add("no readability issues detected by the heuristics");
        return Math.max(0, score);
    }

    // ---- comment/string stripping (keeps offsets roughly stable by replacing with spaces) ------------------------

    static String stripCLike(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                while (i < end) { out.append(s.charAt(i) == '\n' ? '\n' : ' '); i++; }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < s.length() && s.charAt(i) != c && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\') { out.append(' '); i++; }
                    if (i < s.length()) { out.append(' '); i++; }
                }
                if (i < s.length()) { out.append(s.charAt(i)); i++; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static String stripPython(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '#') {
                while (i < s.length() && s.charAt(i) != '\n') { out.append(' '); i++; }
            } else if ((c == '"' || c == '\'') && s.startsWith(String.valueOf(c).repeat(3), i)) {
                String q = String.valueOf(c).repeat(3);
                int end = s.indexOf(q, i + 3);
                end = end < 0 ? s.length() : end + 3;
                out.append("\"\"");
                while (i < end) { if (s.charAt(i) == '\n') out.append('\n'); i++; }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < s.length() && s.charAt(i) != c && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\') { out.append(' '); i++; }
                    if (i < s.length()) { out.append(' '); i++; }
                }
                if (i < s.length()) { out.append(s.charAt(i)); i++; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
