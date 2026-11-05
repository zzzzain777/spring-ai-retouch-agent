package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 没配置 DashScope 密钥（本项目的默认状态）：需要图像模型的工具直接失败并说明原因，不发请求、画布不变。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.worker.poll-interval=200ms",
                "retouch.image.dashscope.api-key="})
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class ModelKeyMissingTests {

    @Autowired
    TestRestTemplate http;

    @Test
    void modelToolsFailWithAReadableReason() throws Exception {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(64, 64, false), "a.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");

        for (var call : List.of(Map.of("tool", "replace_background", "params", Map.of("prompt", "海边")),
                Map.of("tool", "upscale_image", "params", Map.of()))) {
            var run = (Map) client.post("/api/sessions/" + session + "/tools", call).getBody().get("run");
            Map done = waitFor(client, (String) run.get("id"));
            assertThat(done).containsEntry("status", "failed")
                    .containsEntry("error", "未配置 DASHSCOPE_API_KEY，图像模型不可用");
        }
        var generated = client.post("/api/generations", Map.of("prompt", "白底运动鞋"));
        assertThat(waitFor(client, (String) generated.getBody().get("id")))
                .containsEntry("error", "未配置 DASHSCOPE_API_KEY，图像模型不可用");
        assertThat(client.get("/api/sessions/" + session).getBody()).containsEntry("revision", 1);
    }

    private static Map waitFor(ApiClient client, String runId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (true) {
            Map run = client.get("/api/runs/" + runId).getBody();
            if (List.of("succeeded", "failed").contains(run.get("status")) || Instant.now().isAfter(deadline)) {
                return run;
            }
            Thread.sleep(200);
        }
    }
}
