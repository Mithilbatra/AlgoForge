package com.algoforge.learning;

import com.algoforge.auth.CurrentUser;
import com.algoforge.learning.LearningService.ConceptMastery;
import com.algoforge.learning.RecommendationService.Rec;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class LearningController {
    private final LearningService learning;
    private final RecommendationService recommendations;
    private final JdbcClient jdbc;

    public LearningController(LearningService learning, RecommendationService recommendations, JdbcClient jdbc) {
        this.learning = learning;
        this.recommendations = recommendations;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/learning/concepts")
    public List<ConceptMastery> concepts(Authentication auth) {
        return learning.concepts(CurrentUser.id(auth));
    }

    @GetMapping("/api/recommendations/today")
    public List<Rec> today(Authentication auth) {
        return recommendations.today(CurrentUser.id(auth));
    }

    @PostMapping("/api/recommendations/{id}/{action}")
    public Rec act(@PathVariable long id, @PathVariable String action, Authentication auth) {
        return recommendations.act(CurrentUser.id(auth), id, action);
    }

    /** "What next?" after solving a problem: the transfer step, if one exists. */
    @GetMapping("/api/learning/transfer")
    public Map<String, Object> transfer(@RequestParam long problemId, Authentication auth) {
        return recommendations.transferFor(CurrentUser.id(auth), problemId)
                .map(c -> Map.<String, Object>of("problemId", c.problemId(), "conceptCode", c.conceptCode(), "reason", c.reason(),
                        "title", jdbc.sql("select title from problems where id = ?").param(c.problemId()).query(String.class).single()))
                .orElse(Map.of());
    }
}
