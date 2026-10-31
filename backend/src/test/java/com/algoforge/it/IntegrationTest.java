package com.algoforge.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import jakarta.servlet.http.Cookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared Spring context against a real PostgreSQL (Testcontainers), with helpers that use the real auth flow. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    private static final AtomicInteger IPS = new AtomicInteger(1);

    /** The shell's AI keys never reach these tests: scripted providers stand in, and LiveIntegrationTest is the opt-in. */
    @DynamicPropertySource
    static void withoutApiKeys(DynamicPropertyRegistry registry) {
        System.getenv().keySet().stream().filter(k -> k.endsWith("_API_KEY")).forEach(k -> registry.add(k, () -> ""));
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcClient jdbc;

    /** Registers a fresh user (from a fresh client address, so the registration rate limit is not shared) and returns its session cookie. */
    protected Cookie register(String name) throws Exception {
        String ip = "10.0." + (IPS.get() / 250) + "." + (IPS.getAndIncrement() % 250 + 1);
        MvcResult r = mvc.perform(post("/api/auth/register").with(csrf()).with(req -> { req.setRemoteAddr(ip); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", name + System.nanoTime() + "@example.com",
                                "password", "correct horse battery", "displayName", name))))
                .andReturn();
        if (r.getResponse().getStatus() != 200) throw new AssertionError("register failed: " + r.getResponse().getContentAsString());
        Cookie session = r.getResponse().getCookie("AFSESSION");
        if (session == null) throw new AssertionError("no session cookie issued");
        return session;
    }

    protected long userId(Cookie s) throws Exception {
        return body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me").cookie(s)).andReturn()).get("id").asLong();
    }

    protected MockHttpServletRequestBuilder jsonPost(String url, Cookie s, Object body) throws Exception {
        return post(url).cookie(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    protected JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? null : json.readTree(s);
    }

    protected long problemId(String title) {
        return jdbc.sql("select id from problems where title = ? and owner_id is null").param(title).query(Long.class).single();
    }
}
