package io.github.zzzzain777.retouch.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

import io.github.zzzzain777.retouch.ApiClient;
import io.github.zzzzain777.retouch.ImageServiceTestConfiguration;
import io.github.zzzzain777.retouch.TestcontainersConfiguration;

/**
 * 计划里含排队执行的图像工具：确认 → 第 1 步排队 → Worker 调真实 Python 图像服务 → 完成回调推进第 2 步。
 * 规划模型用脚本模型代替。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.worker.poll-interval=200ms"})
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, ImageServiceTestConfiguration.class,
        AgentTests.ScriptedModelConfiguration.class})
@EnabledIf("io.github.zzzzain777.retouch.ImageServiceProcess#available")
@SuppressWarnings({"unchecked", "rawtypes"})
class AgentImageTests {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ScriptedChatModel model;

    private Map waitUntilSettled(ApiClient client, String messages) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (true) {
            var turns = (List<Map>) (List) client.exchange(HttpMethod.GET, messages, null, List.class).getBody();
            Map last = turns.getLast();
            if (!"running".equals(last.get("status")) || Instant.now().isAfter(deadline)) {
                return last;
            }
            Thread.sleep(200);
        }
    }

    private static List<String> history(ApiClient client, String session) {
        var entries = new ArrayList<Map>((List<Map>) (List) client.exchange(HttpMethod.GET,
                "/api/sessions/" + session + "/history", null, List.class).getBody());
        entries.sort(Comparator.comparingInt(entry -> ((Number) entry.get("seq")).intValue()));
        return entries.stream().map(entry -> (String) entry.get("action")).toList();
    }

    @Test
    void queuedImageStepContinuesThePlanWhenItFinishes() throws Exception {
        model.reset();
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(120, 80, false), "a.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        String messages = "/api/sessions/" + session + "/messages";

        model.thenCalls(Map.entry("adjust_image", Map.of("brightness", 0.3)),
                Map.entry("flip_layer", Map.of("direction", "horizontal")));
        Map turn = client.post(messages, Map.of("text", "调亮一点再水平翻转")).getBody();
        assertThat(turn).containsEntry("status", "queued");

        Map confirmed = client.post(messages + "/" + turn.get("id") + "/confirm", Map.of()).getBody();
        assertThat(confirmed).containsEntry("status", "running");
        var first = ((List<Map>) confirmed.get("steps")).getFirst();
        assertThat(first.get("status")).isIn("queued", "running", "succeeded");
        assertThat(first.get("run_id")).isNotNull();

        Map settled = waitUntilSettled(client, messages);
        assertThat(settled).containsEntry("status", "succeeded");
        assertThat(((List<Map>) settled.get("steps")).stream().map(step -> step.get("status")).toList())
                .containsExactly("succeeded", "succeeded");
        assertThat(history(client, session)).containsSubsequence("adjust_image", "flip_layer");
    }

    @Test
    void singleImageStepRunsWithoutConfirmation() throws Exception {
        model.reset();
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(64, 64, false), "b.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        String messages = "/api/sessions/" + session + "/messages";

        model.thenCalls(Map.entry("adjust_image", Map.of("brightness", 0.2, "temperature", 0.1)));
        Map turn = client.post(messages, Map.of("text", "整体调亮一点，暖一点")).getBody();
        assertThat(turn.get("status")).isIn("running", "succeeded");
        assertThat(waitUntilSettled(client, messages)).containsEntry("status", "succeeded");
        assertThat(history(client, session)).last().isEqualTo("adjust_image");
    }
}
