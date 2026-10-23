package com.algoforge.dashboard;

import com.algoforge.auth.CurrentUser;
import com.algoforge.learning.LearningService;
import com.algoforge.learning.LearningService.ConceptMastery;
import com.algoforge.learning.RecommendationService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** One call for the dashboard; every number is computed from the user's own records. */
@RestController
@Profile("!worker")
public class DashboardController {
    private final JdbcClient jdbc;
    private final LearningService learning;
    private final RecommendationService recommendations;

    public DashboardController(JdbcClient jdbc, LearningService learning, RecommendationService recommendations) {
        this.jdbc = jdbc;
        this.learning = learning;
        this.recommendations = recommendations;
    }

    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard(Authentication auth, @RequestParam(required = false) String tz) {
        long uid = CurrentUser.id(auth);
        // Days are counted in the browser's time zone so late-evening practice lands on the right day; unknown zones fall back to UTC.
        String zone = tz != null && ZoneId.getAvailableZoneIds().contains(tz) ? tz : "UTC";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recentSubmissions", jdbc.sql("""
                select s.id, s.problem_id as "problemId", p.title, s.language, s.status, s.passed, s.total, s.created_at as "createdAt",
                       s.oa_session_id as "oaSessionId"
                  from submissions s join problems p on p.id = s.problem_id where s.user_id = ? order by s.id desc limit 6""")
                .param(uid).query().listOfRows());
        out.put("recentAssessments", jdbc.sql("""
                select o.id, d.title, o.status, o.started_at as "startedAt", o.total_score as "totalScore", o.max_score as "maxScore"
                  from oa_sessions o join oa_definitions d on d.id = o.definition_id where o.user_id = ? order by o.id desc limit 4""")
                .param(uid).query().listOfRows());
        out.put("recentEvaluations", jdbc.sql("""
                select e.id, p.title, e.overall_score as "overallScore", e.correctness, e.status
                  from evaluation_reports e join submissions s on s.id = e.submission_id join problems p on p.id = s.problem_id
                 where s.user_id = ? and (s.oa_session_id is null or exists (select 1 from oa_sessions o where o.id = s.oa_session_id and o.status = 'COMPLETED'))
                 order by e.id desc limit 5""").param(uid).query().listOfRows());
        out.put("preferences", jdbc.sql("""
                select goal_text as "goalText", goal_date as "goalDate", goal_concepts::text as "goalConcepts", daily_minutes as "dailyMinutes",
                       show_streak as "showStreak" from user_preferences where user_id = ?""").param(uid).query().singleRow());
        out.put("recommendations", recommendations.today(uid));
        out.put("activity", jdbc.sql("""
                select (created_at at time zone :tz)::date as day, count(*) as judged, count(*) filter (where status = 'ACCEPTED') as accepted
                  from submissions where user_id = :uid and judged_at is not null and status <> 'INTERNAL_ERROR'
                   and created_at > now() - interval '54 weeks'
                 group by 1 order by 1""").param("tz", zone).param("uid", uid).query().listOfRows());

        List<ConceptMastery> concepts = learning.concepts(uid);
        out.put("weakestConcepts", concepts.stream().filter(c -> c.mastery() != null && !c.status().equals("INSUFFICIENT_EVIDENCE"))
                .sorted((a, b) -> Double.compare(a.mastery(), b.mastery())).limit(3).toList());
        out.put("conceptStatusCounts", concepts.stream().collect(java.util.stream.Collectors.groupingBy(ConceptMastery::status,
                java.util.stream.Collectors.counting())));
        out.put("revisionQueue", concepts.stream().filter(ConceptMastery::dueForRevision).map(ConceptMastery::name).toList());

        // Weekly trend: share of judged submissions accepted, and accepted-on-first-attempt share of solved problems.
        out.put("trend", jdbc.sql("""
                with w as (
                  select date_trunc('week', created_at)::date as week, count(*) filter (where status = 'ACCEPTED') as accepted,
                         count(*) as judged
                    from submissions where user_id = ? and judged_at is not null and status not in ('INTERNAL_ERROR')
                     and created_at > now() - interval '8 weeks' group by 1)
                select week, accepted, judged, round(100.0 * accepted / judged, 1) as "acceptRate" from w order by week""")
                .param(uid).query().listOfRows());

        // Time management from completed assessments: points-per-minute by problem outcome.
        out.put("timeInsights", jdbc.sql("""
                select count(*) as assessments, round(avg(100.0 * total_score / nullif(max_score, 0)), 1) as "avgPercent",
                       round(avg(extract(epoch from (ended_at - started_at)) / 60)::numeric, 1) as "avgMinutesUsed"
                  from oa_sessions where user_id = ? and status = 'COMPLETED'""").param(uid).query().singleRow());

        Map<String, Object> prefs = (Map<String, Object>) out.get("preferences");
        if (Boolean.TRUE.equals(prefs.get("showStreak"))) {
            List<LocalDate> days = jdbc.sql("""
                    select distinct created_at::date from submissions where user_id = ? and status = 'ACCEPTED' order by 1 desc limit 400""")
                    .param(uid).query(LocalDate.class).list();
            int streak = 0;
            LocalDate expect = LocalDate.now();
            if (!days.isEmpty() && days.getFirst().equals(expect.minusDays(1))) expect = expect.minusDays(1);
            for (LocalDate d : days) {
                if (!d.equals(expect)) break;
                streak++;
                expect = expect.minusDays(1);
            }
            out.put("streakDays", streak);
        }
        return out;
    }
}
