package com.algoforge.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class Audit {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public Audit(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void log(Long userId, String action, Map<String, ?> detail, String ip) {
        try {
            jdbc.sql("insert into audit_log (user_id, action, detail, ip) values (?, ?, cast(? as jsonb), ?)")
                    .params(userId, action, json.writeValueAsString(detail), ip).update();
        } catch (Exception e) {
            throw new IllegalStateException("audit log write failed", e);
        }
    }
}
