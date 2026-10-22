package com.algoforge.sources;

import java.net.URI;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter for platforms without an official problem-data API we can use: problems are stored as canonical links
 * plus the user's own notes, never scraped.
 */
public class LinkOnlyAdapter implements ProblemSourceAdapter {
    private final String code;
    private final String host;
    private final Pattern path;
    private final Function<String, String> urlForId;
    private final String notes;

    public LinkOnlyAdapter(String code, String host, String pathRegex, Function<String, String> urlForId, String notes) {
        this.code = code;
        this.host = host;
        this.path = Pattern.compile(pathRegex);
        this.urlForId = urlForId;
        this.notes = notes;
    }

    @Override public String code() { return code; }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, false, false, false, true, false, false, false, notes);
    }

    @Override
    public Optional<String> parseExternalId(URI url) {
        String h = url.getHost() == null ? "" : url.getHost().toLowerCase();
        if (!h.equals(host) && !h.equals("www." + host)) return Optional.empty();
        Matcher m = path.matcher(url.getPath() == null ? "" : url.getPath());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    @Override public String canonicalUrl(String externalId) { return urlForId.apply(externalId); }
}
