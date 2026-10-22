package com.algoforge.problems;

import com.algoforge.auth.CurrentUser;
import com.algoforge.common.ApiException;
import com.algoforge.common.Audit;
import com.algoforge.common.RateLimiter;
import com.algoforge.sources.ProblemSourceAdapter;
import com.algoforge.sources.ProblemSourceAdapter.Capabilities;
import com.algoforge.sources.SourceRegistry;
import com.algoforge.sources.SourceSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class ProblemController {
    static final Capabilities ORIGINAL_CAPS = new Capabilities(true, false, true, true, false, false, true, true,
            "Original AlgoForge problems: full statements, tests and local judging. They ship with the app, so there is nothing to sync.");
    static final Capabilities MANUAL_CAPS = new Capabilities(false, false, false, false, true, false, false, false,
            "Links you add from any site: stored with your notes only.");

    private final JdbcClient jdbc;
    private final ProblemService problems;
    private final SourceRegistry sources;
    private final SourceSyncService sync;
    private final RateLimiter limiter;
    private final Audit audit;
    private final ObjectMapper json;

    public ProblemController(JdbcClient jdbc, ProblemService problems, SourceRegistry sources, SourceSyncService sync,
                             RateLimiter limiter, Audit audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.problems = problems;
        this.sources = sources;
        this.sync = sync;
        this.limiter = limiter;
        this.audit = audit;
        this.json = json;
    }

    // ---- catalogue ----------------------------------------------------------------------------------------------

    public record ProblemSummary(long id, String source, String externalId, String canonicalUrl, String title,
                                 String difficulty, Integer sourceRating, Integer estMinutes, String status,
                                 List<String> tags, boolean tagsHidden, boolean judgeable, boolean owned) {}

    public record Page<T>(List<T> items, long total, int page, int size) {}

    // Tags of "disguised" (tier 3) concepts stay hidden until solved, so the tag does not give the technique away.
    private static final String VISIBLE_TAGS = """
            array(select t.tag from problem_tags t where t.problem_id = p.id
                    and (coalesce(ups.status, 'UNSEEN') = 'SOLVED' or not exists (select 1 from problem_concepts pc
                         where pc.problem_id = p.id and pc.concept_code = t.tag and pc.tier = 3))
                  order by t.tag)""";

    @GetMapping("/api/problems")
    public Page<ProblemSummary> list(Authentication auth,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String source,
                                     @RequestParam(required = false) String difficulty,
                                     @RequestParam(required = false) String tag,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Integer minRating,
                                     @RequestParam(required = false) Integer maxRating,
                                     @RequestParam(required = false) Integer maxMinutes,
                                     @RequestParam(required = false) Boolean judgeable,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        Map<String, Object> params = new HashMap<>();
        params.put("uid", CurrentUser.id(auth));
        StringBuilder where = new StringBuilder(" where (p.owner_id is null or p.owner_id = :uid)");
        if (q != null && !q.isBlank()) { where.append(" and p.title ilike :q"); params.put("q", "%" + q.trim() + "%"); }
        if (source != null) { where.append(" and p.source = :source"); params.put("source", source); }
        if (difficulty != null) { where.append(" and p.difficulty = :difficulty"); params.put("difficulty", difficulty); }
        if (tag != null) { where.append(" and :tag = any(" + VISIBLE_TAGS + ")"); params.put("tag", tag); }
        if (status != null) { where.append(" and coalesce(ups.status, 'UNSEEN') = :status"); params.put("status", status); }
        if (minRating != null) { where.append(" and p.source_rating >= :minRating"); params.put("minRating", minRating); }
        if (maxRating != null) { where.append(" and p.source_rating <= :maxRating"); params.put("maxRating", maxRating); }
        if (maxMinutes != null) { where.append(" and p.est_minutes <= :maxMinutes"); params.put("maxMinutes", maxMinutes); }
        String judgeableExpr = "(exists (select 1 from test_cases tc where tc.problem_id = p.id and tc.kind in ('SAMPLE','OFFICIAL'))"
                + " or jsonb_array_length(p.generated_specs) > 0)";
        if (judgeable != null) where.append(judgeable ? " and " : " and not ").append(judgeableExpr);
        String from = " from problems p left join user_problem_state ups on ups.problem_id = p.id and ups.user_id = :uid";

        long total = jdbc.sql("select count(*)" + from + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", page * size);
        List<ProblemSummary> items = jdbc.sql("""
                select p.id, p.source, p.external_id, p.canonical_url, p.title, p.difficulty, p.source_rating, p.est_minutes,
                       coalesce(ups.status, 'UNSEEN') as status, %s as tags,
                       (exists (select 1 from problem_concepts pc where pc.problem_id = p.id and pc.tier = 3)
                            and coalesce(ups.status, 'UNSEEN') <> 'SOLVED') as tags_hidden,
                       %s as judgeable, p.owner_id is not null as owned
                """.formatted(VISIBLE_TAGS, judgeableExpr) + from + where
                + " order by " + judgeableExpr + " desc, p.source_rating nulls first, p.id limit :limit offset :offset")
                .params(params)
                .query((rs, i) -> new ProblemSummary(rs.getLong("id"), rs.getString("source"), rs.getString("external_id"),
                        rs.getString("canonical_url"), rs.getString("title"), rs.getString("difficulty"),
                        (Integer) rs.getObject("source_rating"), (Integer) rs.getObject("est_minutes"), rs.getString("status"),
                        strings(rs.getArray("tags")), rs.getBoolean("tags_hidden"), rs.getBoolean("judgeable"), rs.getBoolean("owned")))
                .list();
        return new Page<>(items, total, page, size);
    }

    public record Sample(String input, String output) {}

    public record ConceptRef(String code, String name, int tier) {}

    public record UserState(String status, String notes, Integer personalDifficulty) {}

    public record ProblemDetail(long id, String source, String sourceName, String externalId, String canonicalUrl,
                                String title, String statementMd, String constraintsMd, String difficulty,
                                Integer sourceRating, Integer estMinutes, int timeLimitMs, int memoryLimitMb,
                                List<String> tags, boolean tagsHidden, List<ConceptRef> concepts, List<Sample> samples,
                                List<String> hints, boolean assessmentLocked, boolean editorialAvailable,
                                boolean judgeable, int officialTests, Capabilities capabilities, UserState state, boolean owned) {}

    @GetMapping("/api/problems/{id}")
    public ProblemDetail detail(@PathVariable long id, Authentication auth) {
        long uid = CurrentUser.id(auth);
        problems.visible(uid, id);
        boolean locked = problems.lockedByAssessment(uid, id);
        boolean solutionsLocked = locked || problems.inRunningAssessment(uid, id);
        return jdbc.sql("""
                select p.*, s.name as source_name, coalesce(ups.status, 'UNSEEN') as status, ups.notes, ups.personal_difficulty,
                       %s as tags, (exists (select 1 from problem_concepts pc where pc.problem_id = p.id and pc.tier = 3)
                            and coalesce(ups.status, 'UNSEEN') <> 'SOLVED') as tags_hidden,
                       (select count(*) from test_cases tc where tc.problem_id = p.id and tc.kind = 'OFFICIAL' and tc.seed is null)
                         + jsonb_array_length(p.generated_specs) as official
                  from problems p join problem_sources s on s.code = p.source
                  left join user_problem_state ups on ups.problem_id = p.id and ups.user_id = :uid
                 where p.id = :id""".formatted(VISIBLE_TAGS))
                .param("uid", uid).param("id", id)
                .query((rs, i) -> toDetail(rs, locked, solutionsLocked)).single();
    }

    private ProblemDetail toDetail(ResultSet rs, boolean locked, boolean solutionsLocked) throws SQLException {
        long id = rs.getLong("id");
        boolean solved = "SOLVED".equals(rs.getString("status"));
        boolean hideTags = locked || rs.getBoolean("tags_hidden");
        List<ConceptRef> concepts = locked ? List.of() : jdbc.sql("""
                select c.code, c.name, pc.tier from problem_concepts pc join concepts c on c.code = pc.concept_code
                 where pc.problem_id = ? and (pc.tier < 3 or ?) order by pc.tier, c.sort_order""")
                .params(id, solved).query(ConceptRef.class).list();
        List<Sample> samples = jdbc.sql("""
                select input, expected_output as output from test_cases where problem_id = ? and kind = 'SAMPLE' order by ordinal""")
                .param(id).query(Sample.class).list();
        List<String> hints = new ArrayList<>();
        try {
            json.readTree(rs.getString("hints")).forEach(h -> hints.add(h.asText()));
        } catch (Exception e) {
            throw new IllegalStateException("bad hints json for problem " + id, e);
        }
        String source = rs.getString("source");
        Capabilities caps = switch (source) {
            case "ORIGINAL" -> ORIGINAL_CAPS;
            case "MANUAL" -> MANUAL_CAPS;
            default -> sources.get(source).map(ProblemSourceAdapter::capabilities).orElse(MANUAL_CAPS);
        };
        Integer pd = (Integer) rs.getObject("personal_difficulty");
        return new ProblemDetail(id, source, rs.getString("source_name"), rs.getString("external_id"),
                rs.getString("canonical_url"), rs.getString("title"), rs.getString("statement_md"),
                rs.getString("constraints_md"), rs.getString("difficulty"), (Integer) rs.getObject("source_rating"),
                (Integer) rs.getObject("est_minutes"), rs.getInt("time_limit_ms"), rs.getInt("memory_limit_mb"),
                hideTags ? List.of() : strings(rs.getArray("tags")), hideTags, concepts, samples,
                locked ? null : hints, locked, !solutionsLocked && rs.getString("editorial_md") != null,
                problems.judgeable(id), rs.getInt("official"), caps,
                new UserState(rs.getString("status"), rs.getString("notes"), pd), rs.getObject("owner_id") != null);
    }

    public record Editorial(String editorialMd, String referenceLanguage, String referenceSolution) {}

    @GetMapping("/api/problems/{id}/editorial")
    @Transactional
    public Editorial editorial(@PathVariable long id, Authentication auth) {
        long uid = CurrentUser.id(auth);
        problems.visible(uid, id);
        if (problems.inRunningAssessment(uid, id)) throw ApiException.forbidden("Solutions are hidden until the assessment ends");
        Editorial e = jdbc.sql("select editorial_md, 'python' as reference_language, reference_py as reference_solution from problems where id = ?")
                .param(id).query(Editorial.class).single();
        if (e.editorialMd() == null) throw ApiException.notFound("Editorial");
        problems.markRevealed(uid, id);
        return e;
    }

    public record StateUpdate(@Pattern(regexp = "UNSEEN|ATTEMPTED|SOLVED|NEEDS_REVISION") String status,
                              @Size(max = 5000) String notes, @Min(1) @Max(5) Integer personalDifficulty) {}

    @PutMapping("/api/problems/{id}/state")
    public UserState updateState(@PathVariable long id, @Valid @RequestBody StateUpdate body, Authentication auth) {
        long uid = CurrentUser.id(auth);
        problems.visible(uid, id);
        String status = body.status() == null ? "UNSEEN" : body.status();
        jdbc.sql("""
                insert into user_problem_state (user_id, problem_id, status, notes, personal_difficulty, solved_at)
                values (:uid, :pid, :status, :notes, :pd, case when :status = 'SOLVED' then now() end)
                on conflict (user_id, problem_id) do update set status = excluded.status, notes = excluded.notes,
                    personal_difficulty = excluded.personal_difficulty, updated_at = now(),
                    solved_at = coalesce(user_problem_state.solved_at, excluded.solved_at)""")
                .param("uid", uid).param("pid", id).param("status", status).param("notes", body.notes())
                .param("pd", body.personalDifficulty()).update();
        return new UserState(status, body.notes(), body.personalDifficulty());
    }

    // ---- adding problems -----------------------------------------------------------------------------------------

    public record LinkRequest(@NotBlank @Size(max = 500) String url, @Size(max = 200) String title,
                              @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty) {}

    public record LinkResult(long id, boolean created, String source, String canonicalUrl) {}

    /** Adds a problem by URL. Deduplicates on platform id first, then on canonical URL. */
    @PostMapping("/api/problems/link")
    @Transactional
    public LinkResult link(@Valid @RequestBody LinkRequest body, Authentication auth) {
        long uid = CurrentUser.id(auth);
        URI url;
        try {
            url = URI.create(body.url().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Not a valid URL");
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) && !"http".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
            throw ApiException.badRequest("Only http(s) links are supported");
        }
        Optional<SourceRegistry.Match> match = sources.match(url);
        String source = match.map(m -> m.adapter().code()).orElse("MANUAL");
        String externalId = match.map(SourceRegistry.Match::externalId).orElse(null);
        String canonical = match.map(SourceRegistry.Match::canonicalUrl).orElseGet(() -> normalise(url));

        Optional<Long> existing = jdbc.sql("""
                select id from problems where (owner_id is null or owner_id = :uid)
                   and ((cast(:ext as text) is not null and source = :src and external_id = :ext) or canonical_url = :url)
                 order by owner_id nulls first limit 1""")
                .param("uid", uid).param("ext", externalId).param("src", source).param("url", canonical)
                .query(Long.class).optional();
        if (existing.isPresent()) return new LinkResult(existing.get(), false, source, canonical);

        String title = body.title() != null && !body.title().isBlank() ? body.title().trim() : titleFrom(source, externalId, url);
        try {
            long id = jdbc.sql("""
                    insert into problems (source, external_id, canonical_url, title, difficulty, owner_id)
                    values (?, ?, ?, ?, ?, ?) returning id""")
                    .params(source, externalId, canonical, title, body.difficulty(), uid).query(Long.class).single();
            return new LinkResult(id, true, source, canonical);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("This problem was just added; refresh and try again");
        }
    }

    static String normalise(URI url) {
        String path = url.getPath() == null ? "" : url.getPath().replaceAll("/+$", "");
        return url.getScheme().toLowerCase(Locale.ROOT) + "://" + url.getHost().toLowerCase(Locale.ROOT)
                + (url.getPort() > 0 ? ":" + url.getPort() : "") + path;
    }

    static String titleFrom(String source, String externalId, URI url) {
        if (externalId != null && externalId.matches("[a-z0-9-]+")) {
            StringBuilder sb = new StringBuilder();
            for (String w : externalId.split("-")) if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            return sb.toString().trim();
        }
        if (externalId != null) return source.charAt(0) + source.substring(1).toLowerCase(Locale.ROOT) + " " + externalId;
        String t = url.getHost() + (url.getPath() == null ? "" : url.getPath());
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    public record TestInput(@NotBlank @Size(max = 1_000_000) String input, @NotBlank @Size(max = 1_000_000) String output) {}

    public record OriginalRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 20000) String statementMd,
                                  @Size(max = 2000) String constraintsMd, @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty,
                                  @Min(250) @Max(10000) int timeLimitMs, @Min(16) @Max(1024) int memoryLimitMb,
                                  @Size(max = 10) List<@Size(max = 40) String> tags,
                                  @NotEmpty @Size(max = 5) List<@Valid TestInput> samples,
                                  @Size(max = 50) List<@Valid TestInput> tests) {}

    /** A private original problem; its tests are author-provided (no reference solution, so generated tests stay unverified). */
    @PostMapping("/api/problems")
    @Transactional
    public LinkResult createOriginal(@Valid @RequestBody OriginalRequest body, Authentication auth) {
        long uid = CurrentUser.id(auth);
        limiter.check("create-problem:" + uid, 10, Duration.ofHours(1));
        long id = jdbc.sql("""
                insert into problems (source, title, statement_md, constraints_md, difficulty, time_limit_ms, memory_limit_mb, owner_id)
                values ('ORIGINAL', ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(body.title().trim(), body.statementMd(), body.constraintsMd(), body.difficulty(), body.timeLimitMs(),
                        body.memoryLimitMb(), uid).query(Long.class).single();
        if (body.tags() != null) body.tags().stream().map(String::trim).filter(t -> !t.isEmpty()).distinct().forEach(t ->
                jdbc.sql("insert into problem_tags (problem_id, tag) values (?, ?)").params(id, t).update());
        int ordinal = 0;
        for (TestInput t : body.samples()) insertTest(id, "SAMPLE", ordinal++, t);
        if (body.tests() != null) for (TestInput t : body.tests()) insertTest(id, "OFFICIAL", ordinal++, t);
        return new LinkResult(id, true, "ORIGINAL", null);
    }

    private void insertTest(long problemId, String kind, int ordinal, TestInput t) {
        jdbc.sql("""
                insert into test_cases (problem_id, kind, ordinal, input, expected_output, verification, provenance)
                values (?, ?, ?, ?, ?, 'AUTHOR', 'problem author')""")
                .params(problemId, kind, ordinal, t.input(), t.output()).update();
    }

    // ---- sources --------------------------------------------------------------------------------------------------

    public record SourceInfo(String code, String name, String homepage, Capabilities capabilities, OffsetDateTime lastSyncAt,
                             String lastSyncStatus, String lastSyncMessage, Integer lastSyncCount, long problemCount) {}

    @GetMapping("/api/sources")
    public List<SourceInfo> sources(Authentication auth) {
        return jdbc.sql("""
                select s.*, (select count(*) from problems p where p.source = s.code and (p.owner_id is null or p.owner_id = ?)) as n
                  from problem_sources s order by s.code = 'ORIGINAL' desc, s.name""")
                .param(CurrentUser.id(auth))
                .query((rs, i) -> {
                    String code = rs.getString("code");
                    Capabilities caps = switch (code) {
                        case "ORIGINAL" -> ORIGINAL_CAPS;
                        case "MANUAL" -> MANUAL_CAPS;
                        default -> sources.get(code).map(ProblemSourceAdapter::capabilities).orElse(MANUAL_CAPS);
                    };
                    return new SourceInfo(code, rs.getString("name"), rs.getString("homepage"), caps,
                            rs.getObject("last_sync_at", OffsetDateTime.class), rs.getString("last_sync_status"),
                            rs.getString("last_sync_message"), (Integer) rs.getObject("last_sync_count"), rs.getLong("n"));
                }).list();
    }

    @PostMapping("/api/sources/{code}/sync")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Map<String, Object> syncSource(@PathVariable String code, Authentication auth) {
        limiter.check("sync:" + code, 1, Duration.ofMinutes(10));
        sync.start(code);
        audit.log(CurrentUser.id(auth), "SOURCE_SYNC", Map.of("source", code), null);
        return Map.of("source", code, "status", "RUNNING");
    }

    static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }
}
