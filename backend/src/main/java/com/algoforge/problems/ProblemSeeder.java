package com.algoforge.problems;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the original problems under resources/seed/problems on API start-up.
 * shortcut: inserts missing problems only; edits to an existing seed problem need a migration or a manual delete.
 */
@Component
@Profile("!worker")
public class ProblemSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProblemSeeder.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public ProblemSeeder(JdbcClient jdbc, ObjectMapper json, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        int added = 0;
        for (Resource meta : resolver.getResources("classpath*:seed/problems/*/problem.json")) {
            String slug = slugOf(meta);
            boolean exists = jdbc.sql("select count(*) from problems where slug = ?").param(slug).query(Long.class).single() > 0;
            if (exists) continue;
            String s = slug;
            tx.executeWithoutResult(t -> insert(s, meta));
            added++;
        }
        if (added > 0) log.info("Seeded {} original problems", added);
    }

    private static String slugOf(Resource meta) throws IOException {
        String path = meta.getURL().getPath();
        String[] parts = path.split("/");
        return parts[parts.length - 2];
    }

    private void insert(String slug, Resource meta) {
        try {
            JsonNode p = json.readTree(meta.getInputStream());
            long id = jdbc.sql("""
                    insert into problems (source, slug, title, statement_md, constraints_md, editorial_md, hints, difficulty,
                        est_minutes, time_limit_ms, memory_limit_mb, max_n, input_size_power, reference_py, generator_py,
                        validator_py, generated_specs)
                    values ('ORIGINAL', ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                    returning id""")
                    .params(slug, p.get("title").asText(), read(meta, "statement.md"), p.get("constraints").asText(),
                            read(meta, "editorial.md"), p.get("hints").toString(), p.get("difficulty").asText(),
                            p.get("estMinutes").asInt(), p.get("timeLimitMs").asInt(), p.get("memoryLimitMb").asInt(),
                            p.get("maxN").asInt(), p.path("inputSizePower").asInt(1), read(meta, "ref.py"),
                            read(meta, "gen.py"), read(meta, "validate.py"), p.get("generated").toString())
                    .query(Long.class).single();
            p.get("concepts").fields().forEachRemaining(c -> {
                jdbc.sql("insert into problem_concepts (problem_id, concept_code, tier) values (?, ?, ?)")
                        .params(id, c.getKey(), c.getValue().asInt()).update();
                jdbc.sql("insert into problem_tags (problem_id, tag) values (?, ?)").params(id, c.getKey()).update();
            });
            Set<String> samples = new HashSet<>();
            p.get("samples").forEach(n -> samples.add(n.asText()));
            Resource[] inputs = resolver.getResources("classpath*:seed/problems/" + slug + "/tests/*.in");
            Arrays.sort(inputs, Comparator.comparing(Resource::getFilename));
            int ordinal = 0;
            for (Resource in : inputs) {
                String name = in.getFilename().replace(".in", "");
                String expected = in.createRelative(name + ".out").getContentAsString(StandardCharsets.UTF_8);
                jdbc.sql("""
                        insert into test_cases (problem_id, kind, ordinal, input, expected_output, verification, provenance)
                        values (?, ?, ?, ?, ?, 'AUTHOR', 'author fixture ' || ?)""")
                        .params(id, samples.contains(name) ? "SAMPLE" : "OFFICIAL", ordinal++,
                                in.getContentAsString(StandardCharsets.UTF_8), expected, name)
                        .update();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot seed problem " + slug, e);
        }
    }

    private static String read(Resource meta, String name) throws IOException {
        return meta.createRelative(name).getContentAsString(StandardCharsets.UTF_8);
    }
}
