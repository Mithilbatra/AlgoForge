package com.algoforge.ai;

import com.fasterxml.jackson.databind.JsonNode;

/** A model vendor behind one interface; business logic never sees vendor request shapes. */
public interface AiProvider {

    /** Matches ai_provider_configs.provider. */
    String name();

    /** True when an API key is configured in the environment. */
    boolean configured();

    /** One structured completion: the reply must be JSON matching {@code schema}. */
    Completion complete(String model, String system, String user, JsonNode schema, int maxOutputTokens) throws AiException;

    record Completion(String text, String model, Integer inputTokens, Integer outputTokens) {}
}
