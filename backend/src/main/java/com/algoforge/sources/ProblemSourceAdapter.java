package com.algoforge.sources;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** One adapter per external platform, so a change on one platform cannot break the others. */
public interface ProblemSourceAdapter {

    /** Matches problem_sources.code. */
    String code();

    Capabilities capabilities();

    /** Extracts the platform's problem id from a URL on that platform, or empty when the URL is not a problem page. */
    Optional<String> parseExternalId(URI url);

    String canonicalUrl(String externalId);

    /** Full catalogue metadata; only called when {@code capabilities().metadataSync()} is true. */
    default List<ExternalProblem> fetchCatalogue() throws Exception {
        throw new UnsupportedOperationException(code() + " has no catalogue sync");
    }

    record Capabilities(boolean discovery, boolean metadataSync, boolean statementAccess, boolean constraintsAndExamples,
                        boolean submissionLinks, boolean verdictSync, boolean judging, boolean ratingAndTags, String notes) {}

    record ExternalProblem(String externalId, String title, Integer rating, List<String> tags) {}
}
