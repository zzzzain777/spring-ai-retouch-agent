package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.zzzzain777.retouch.tool.RunWorker;

/** 图像工具全链路：Java 排队 → Worker 领取 → 真实 Python 图像服务读写对象存储 → Java 登记素材、改文档、写历史 → SSE。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.worker.poll-interval=200ms"})
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, ImageServiceTestConfiguration.class})
@EnabledIf("io.github.zzzzain777.retouch.ImageServiceProcess#available")
@SuppressWarnings({"unchecked", "rawtypes"})
class ImageToolTests {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RunWorker worker;

    private record Fixture(ApiClient client, String sessionId, String assetId) {
    }

    private Fixture fixture() {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(120, 80, true), "a.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        return new Fixture(client, session, asset);
    }

    private static Map adjust(Fixture f, Map<String, Object> params) {
        var response = f.client().post("/api/sessions/" + f.sessionId() + "/tools",
                Map.of("tool", "adjust_image", "params", params));
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return (Map) response.getBody().get("run");
    }

    @Test
    void adjustRunsThroughQueuePythonAndSse() throws Exception {
        var f = fixture();
        var run = adjust(f, Map.of("brightness", 0.2));
        assertThat(run).containsEntry("status", "queued").containsEntry("tool", "adjust_image");

        var frames = f.client().sse("/events/runs/" + run.get("id"));
        assertThat(frames.size()).isGreaterThanOrEqualTo(2);
        assertThat(frames.getLast()).containsEntry("status", "succeeded").containsEntry("progress", 100);
        assertThat(frames.stream().map(m -> m.get("status")).toList()).contains("running");

        var session = f.client().get("/api/sessions/" + f.sessionId()).getBody();
        var layer = ((List<Map>) ((Map) session.get("document")).get("layers")).getFirst();
        String produced = (String) layer.get("asset_id");
        assertThat(produced).isNotEqualTo(f.assetId());
        assertThat(session).containsEntry("current_asset_id", produced).containsEntry("revision", 2)
                .containsEntry("history_seq", 2);
        assertThat(((List<Map>) session.get("assets")).stream().map(m -> m.get("id")).toList())
                .containsExactly(f.assetId(), produced);

        var asset = f.client().get("/api/assets/" + produced).getBody();
        assertThat(asset).containsEntry("kind", "generated").containsEntry("source", "tool")
                .containsEntry("width", 120).containsEntry("height", 80).containsEntry("image_format", "PNG")
                .containsEntry("has_alpha", true);
        String key = jdbc.sql("select storage_key from assets where id = ?::uuid").param(produced)
                .query(String.class).single();
        assertThat(key).isEqualTo("runs/" + run.get("id") + "/1/adjusted.png");

        var history = (Map) f.client().exchange(HttpMethod.GET, "/api/sessions/" + f.sessionId() + "/history", null,
                List.class).getBody().getFirst();
        assertThat(history).containsEntry("action", "adjust_image");
        assertThat((Map) history.get("params")).containsEntry("brightness", 0.2).containsEntry("contrast", 0.0);

        var fetched = f.client().get("/api/runs/" + run.get("id")).getBody();
        assertThat(((List<Map>) fetched.get("candidates")).stream().map(m -> m.get("id")).toList())
                .containsExactly(produced);

        // 撤销回到原图
        var undone = f.client().post("/api/sessions/" + f.sessionId() + "/undo", null).getBody();
        assertThat(undone).containsEntry("current_asset_id", f.assetId());
    }

    @Test
    void expiredLeaseIsRequeuedForIdempotentToolsAndFailsWhenAttemptsAreUsedUp() throws Exception {
        var f = fixture();
        UUID userId = jdbc.sql("select user_id from edit_sessions where id = ?::uuid").param(f.sessionId())
                .query(UUID.class).single();
        UUID retried = insertRunning(userId, f.sessionId(), 1, 2);
        UUID exhausted = insertRunning(userId, f.sessionId(), 2, 2);

        worker.recoverExpired();

        assertThat(status(exhausted)).isEqualTo("failed");
        assertThat(jdbc.sql("select error from tool_runs where id = ?").param(exhausted).query(String.class).single())
                .isEqualTo("执行中断，请重试");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!"succeeded".equals(status(retried)) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(status(retried)).isEqualTo("succeeded");
        assertThat(jdbc.sql("select attempt from tool_runs where id = ?").param(retried).query(Integer.class)
                .single()).isEqualTo(2);
        String key = jdbc.sql("select storage_key from assets where storage_key like ?").param("runs/" + retried + "/%")
                .query(String.class).single();
        assertThat(key).isEqualTo("runs/" + retried + "/2/adjusted.png");
    }

    private UUID insertRunning(UUID userId, String sessionId, int attempt, int maxAttempts) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into tool_runs (id, user_id, session_id, tool, status, progress, stage, params, result,
                                       attempt, max_attempts, lease_until, worker_id, started_at)
                values (?, ?, ?::uuid, 'adjust_image', 'running', 20, '读取图层',
                        '{"layer_id": null, "brightness": 0.1}'::jsonb, '{}'::jsonb, ?, ?,
                        now() - interval '1 minute', 'crashed-worker', now() - interval '2 minutes')
                """).params(id, userId, sessionId, attempt, maxAttempts).update();
        return id;
    }

    private String status(UUID id) {
        return jdbc.sql("select status from tool_runs where id = ?").param(id).query(String.class).single();
    }
}
