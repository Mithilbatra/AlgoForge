package com.algoforge;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("algoforge")
public record AppProperties(Worker worker, Sandbox sandbox, Scoring scoring, Ai ai, Limits limits) {

    public record Worker(int threads, long pollIntervalMs, int staleAfterSeconds, int maxAttempts) {}

    public record Sandbox(String docker, String runtime, int pidsLimit, int outputLimitKb,
                          int compileTimeoutSeconds, Map<String, String> images) {}

    /** Dimension key -> weight; normalised when scoring, so they need not sum to 100. */
    public record Scoring(Map<String, Integer> weights) {}

    public record Ai(String claudeApiKey, String geminiApiKey, boolean dualMode, int timeoutSeconds,
                     int requestsPerUserPerHour, int cacheDays) {}

    public record Limits(int submissionsPerMinute, int runsPerMinute, int loginsPer15Minutes, int registrationsPer15Minutes) {}
}
