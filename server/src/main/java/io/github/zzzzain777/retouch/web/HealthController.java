package io.github.zzzzain777.retouch.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import io.github.zzzzain777.retouch.storage.ObjectStorage;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 与上游契约一致的 {@code GET /api/health}：api、database、storage 三项，另加 redis（上游未探测 Redis）。
 * 任一依赖异常时该项为 {@code error: 异常类名}，接口本身仍返回 200，便于前端和评测读到具体原因。
 */
@RestController
class HealthController {

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final RedisConnectionFactory redis;

    HealthController(JdbcClient jdbc, ObjectStorage storage, RedisConnectionFactory redis) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.redis = redis;
    }

    @GetMapping("/api/health")
    Map<String, String> health() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("api", "ok");
        result.put("database", probe(() -> jdbc.sql("select 1").query(Integer.class).single()));
        result.put("storage", probe(() -> {
            storage.ensureBucket();
            return null;
        }));
        result.put("redis", probe(() -> {
            try (var connection = redis.getConnection()) {
                return connection.ping();
            }
        }));
        return result;
    }

    private static String probe(Callable<?> check) {
        try {
            check.call();
            return "ok";
        } catch (Exception exc) {
            return "error: " + exc.getClass().getSimpleName();
        }
    }
}
