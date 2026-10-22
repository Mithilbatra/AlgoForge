package com.algoforge.auth;

import com.algoforge.AppProperties;
import com.algoforge.common.ApiException;
import com.algoforge.common.Audit;
import com.algoforge.common.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class AuthController {
    // Compared against when the email is unknown, so response time does not reveal which emails exist.
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z3LgXQyEUEJ2xC4sdDHb8wIa";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final SecurityContextRepository contexts;
    private final RateLimiter limiter;
    private final AppProperties props;
    private final Audit audit;

    public AuthController(JdbcClient jdbc, PasswordEncoder encoder, SecurityContextRepository contexts,
                          RateLimiter limiter, AppProperties props, Audit audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.contexts = contexts;
        this.limiter = limiter;
        this.props = props;
        this.audit = audit;
    }

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 8, max = 72) String password,
                                  @NotBlank @Size(max = 60) String displayName) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record Me(long id, String email, String displayName, String role) {}

    record UserRow(long id, String email, String displayName, String passwordHash, String role) {}

    @GetMapping("/api/auth/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void csrf() { /* the CSRF cookie filter issues the token */ }

    @PostMapping("/api/auth/register")
    @Transactional
    public Me register(@Valid @RequestBody RegisterRequest body, HttpServletRequest req, HttpServletResponse res) {
        // BCrypt reads at most 72 bytes and the encoder rejects longer input; accented letters and symbols take several bytes.
        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("Password is too long: use at most 72 bytes (fewer characters if it has accents or symbols)");
        }
        limiter.check("register:" + req.getRemoteAddr(), props.limits().registrationsPer15Minutes(), Duration.ofMinutes(15));
        // Client addresses can be forged when no trusted proxy sets them, so registration also has a global ceiling.
        limiter.check("register:all", props.limits().registrationsPer15Minutes() * 40, Duration.ofMinutes(15));
        long id;
        try {
            id = jdbc.sql("insert into users (email, display_name, password_hash) values (?, ?, ?) returning id")
                    .params(body.email().trim(), body.displayName().trim(), encoder.encode(body.password()))
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An account with this email already exists");
        }
        jdbc.sql("insert into user_preferences (user_id) values (?)").param(id).update();
        audit.log(id, "REGISTER", Map.of(), req.getRemoteAddr());
        signIn(new UserRow(id, body.email().trim(), body.displayName().trim(), null, "USER"), req, res);
        return new Me(id, body.email().trim(), body.displayName().trim(), "USER");
    }

    @PostMapping("/api/auth/login")
    public Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        // Per account (cannot be dodged by changing address) and per client address.
        limiter.check("login-account:" + body.email().trim().toLowerCase(), props.limits().loginsPer15Minutes(), Duration.ofMinutes(15));
        limiter.check("login:" + req.getRemoteAddr(), props.limits().loginsPer15Minutes() * 3, Duration.ofMinutes(15));
        Optional<UserRow> user = jdbc.sql("select id, email, display_name, password_hash, role from users where lower(email) = lower(?)")
                .param(body.email().trim()).query(UserRow.class).optional();
        boolean ok = encoder.matches(body.password(), user.map(UserRow::passwordHash).orElse(DUMMY_HASH));
        if (user.isEmpty() || !ok) {
            audit.log(user.map(UserRow::id).orElse(null), "LOGIN_FAILED", Map.of(), req.getRemoteAddr());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        signIn(user.get(), req, res);
        audit.log(user.get().id(), "LOGIN", Map.of(), req.getRemoteAddr());
        return new Me(user.get().id(), user.get().email(), user.get().displayName(), user.get().role());
    }

    @GetMapping("/api/auth/me")
    public Me me(Authentication auth) {
        return jdbc.sql("select id, email, display_name, role from users where id = ?")
                .param(CurrentUser.id(auth)).query(Me.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Session expired"));
    }

    private void signIn(UserRow user, HttpServletRequest req, HttpServletResponse res) {
        if (req.getSession(false) != null) req.changeSessionId();  // session fixation defence
        var token = UsernamePasswordAuthenticationToken.authenticated(String.valueOf(user.id()), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(token);
        SecurityContextHolder.setContext(ctx);
        contexts.saveContext(ctx, req, res);
    }

    // ---- preferences ------------------------------------------------------------------------------------------

    public record Preferences(@Pattern(regexp = "java|cpp|python") String preferredLanguage,
                              @Min(10) @Max(480) int dailyMinutes,
                              @Pattern(regexp = "EASY|MEDIUM|HARD") String targetDifficulty,
                              @Size(max = 200) String goalText,
                              LocalDate goalDate,
                              List<@Size(max = 40) String> goalConcepts,
                              boolean showStreak) {}

    @GetMapping("/api/me/preferences")
    public Preferences preferences(Authentication auth) {
        return jdbc.sql("""
                select preferred_language, daily_minutes, target_difficulty, goal_text, goal_date,
                       goal_concepts, show_streak from user_preferences where user_id = ?""")
                .param(CurrentUser.id(auth))
                .query((rs, i) -> new Preferences(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class), List.of((String[]) rs.getArray(6).getArray()), rs.getBoolean(7)))
                .single();
    }

    @PutMapping("/api/me/preferences")
    public Preferences updatePreferences(@Valid @RequestBody Preferences p, Authentication auth) {
        List<String> concepts = p.goalConcepts() == null ? List.of() : p.goalConcepts();
        long known = concepts.isEmpty() ? 0 : jdbc.sql("select count(*) from concepts where code = any(?)")
                .param(concepts.toArray(String[]::new)).query(Long.class).single();
        if (known != concepts.size()) throw ApiException.badRequest("Unknown concept in goalConcepts");
        jdbc.sql("""
                update user_preferences set preferred_language = ?, daily_minutes = ?, target_difficulty = ?,
                       goal_text = ?, goal_date = ?, goal_concepts = ?, show_streak = ?, updated_at = now()
                 where user_id = ?""")
                .params(p.preferredLanguage(), p.dailyMinutes(), p.targetDifficulty(), p.goalText(), p.goalDate(),
                        concepts.toArray(String[]::new), p.showStreak(), CurrentUser.id(auth))
                .update();
        return preferences(auth);
    }
}
