package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.zzzzain777.retouch.tool.ToolService;

/** Worker 关闭时任务停在队列里；取消排队任务立即生效，SSE 只发一帧终态快照就断开。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.worker.enabled=false"})
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class QueueCancelTests {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ToolService tools;

    @Test
    void canceledQueuedRunNeverExecutes() throws Exception {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(64, 64, false), "a.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        var run = (Map) client.post("/api/sessions/" + session + "/tools",
                Map.of("tool", "adjust_image", "params", Map.of("saturation", 0.5))).getBody().get("run");
        assertThat(client.get("/api/runs/" + run.get("id")).getBody()).containsEntry("status", "queued");

        assertThat(tools.requestCancel(UUID.fromString((String) run.get("id"))).getStatus().value())
                .isEqualTo("canceled");
        var frames = client.sse("/events/runs/" + run.get("id"));
        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst()).containsEntry("status", "canceled");
        assertThat(client.get("/api/sessions/" + session).getBody()).containsEntry("history_seq", 1);
    }
}
