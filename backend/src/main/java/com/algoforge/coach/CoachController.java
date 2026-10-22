package com.algoforge.coach;

import com.algoforge.auth.CurrentUser;
import com.algoforge.common.RateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class CoachController {
    private final CoachService coach;
    private final RateLimiter limiter;

    public CoachController(CoachService coach, RateLimiter limiter) {
        this.coach = coach;
        this.limiter = limiter;
    }

    @PostMapping("/api/problems/{id}/coach")
    public CoachService.Reply ask(@PathVariable long id, @Valid @RequestBody CoachService.Request body, Authentication auth) {
        long uid = CurrentUser.id(auth);
        limiter.check("coach:" + uid, 40, Duration.ofHours(1));
        return coach.coach(uid, id, body);
    }
}
