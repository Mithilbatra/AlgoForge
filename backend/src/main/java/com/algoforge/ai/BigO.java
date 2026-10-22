package com.algoforge.ai;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses common big-O strings into (power of n, power of log n) so claims can be compared. */
public final class BigO {
    private BigO() {}

    public record Growth(double nPower, int logPower, boolean exponential) {
        public boolean sameClass(Growth o) {
            return exponential == o.exponential && Math.abs(nPower - o.nPower) < 0.01 && logPower == o.logPower;
        }
    }

    private static final Pattern POWER = Pattern.compile("([nmvrc]|\\(n\\+m\\)|\\(v\\+e\\))\\^(\\d+(?:\\.\\d+)?)");

    public static Optional<Growth> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.toLowerCase(Locale.ROOT).replace(" ", "").replace("²", "^2").replace("³", "^3")
                .replace("·", "*").replace("⋅", "*").replace("×", "*").replace("lg", "log").replace("log(n)", "logn")
                .replace("logn^2", "log^2n").replace("(logn)^2", "log^2n").replace("\\log", "log");
        Matcher wrap = Pattern.compile("^[θo]\\((.*)\\)$").matcher(s);
        if (!wrap.matches()) return Optional.empty();
        s = wrap.group(1);
        if (s.contains("2^n") || s.contains("!") || s.contains("^n")) return Optional.of(new Growth(99, 0, true));
        int log = 0;
        Matcher lp = Pattern.compile("log\\^(\\d)").matcher(s);
        while (lp.find()) log += Integer.parseInt(lp.group(1));
        s = s.replaceAll("log\\^\\d[nmv]?", "");
        Matcher l = Pattern.compile("log[nmvke]?").matcher(s);
        while (l.find()) log++;
        s = s.replaceAll("log[nmvke]?", "");
        double n = 0;
        Matcher p = POWER.matcher(s);
        while (p.find()) n += Double.parseDouble(p.group(2));
        s = POWER.matcher(s).replaceAll("");
        s = s.replace("(n+m)", "n").replace("(v+e)", "n").replace("n+m", "n").replace("v+e", "n").replace("sqrt(n)", "√");
        for (char c : s.toCharArray()) {
            if ("nmverc".indexOf(c) >= 0) n += 1;
            else if (c == '√') n += 0.5;
            else if ("*()1+kq".indexOf(c) < 0) return Optional.empty();
        }
        return Optional.of(new Growth(n, log, false));
    }
}
