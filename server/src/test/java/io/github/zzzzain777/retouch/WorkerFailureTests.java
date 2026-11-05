package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 图像服务不可达时：任务记为 failed 并给出可读原因，画布不变；SSE 照样送达终态。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.worker.poll-interval=200ms",
                "retouch.image-service.base-url=http://127.0.0.1:9"})
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class WorkerFailureTests {

    @Autowired
    TestRestTemplate http;

    @Test
    void unreachableImageServiceFailsTheRunReadably() throws Exception {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(64, 64, false), "a.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        var run = (Map) client.post("/api/sessions/" + session + "/tools",
                Map.of("tool", "adjust_image", "params", Map.of("contrast", 0.3))).getBody().get("run");

        var frames = client.sse("/events/runs/" + run.get("id"));
        assertThat(frames.getLast()).containsEntry("status", "failed").containsEntry("error", "图像服务不可用");
        assertThat(client.get("/api/sessions/" + session).getBody()).containsEntry("history_seq", 1)
                .containsEntry("current_asset_id", asset);
        assertThat(ApiClient.registered(http).get("/events/runs/" + run.get("id")).getStatusCode().value())
                .isEqualTo(404);
    }
}
