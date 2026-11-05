package io.github.zzzzain777.retouch.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.ApiClient;
import io.github.zzzzain777.retouch.TestcontainersConfiguration;

/**
 * 真实规划模型的集成检查（不是评测）：走完整 HTTP 接口，规划模型用 application-local.yml 或环境变量里的真实密钥。
 * 默认不运行，需显式打开：{@code ./mvnw test -Dtest=RealPlannerCheck -Dretouch.real-planner=true}，
 * 加 {@code -Dretouch.planner.follow-up-rounds=1} 检查追问一轮的效果。
 * <p>
 * 只有 5 条冒烟用例，断言集成是否打通（请求成功、拿到结构化计划、多步计划停在待确认且不派发）；
 * 每条的工具与用量写进 {@code target/real-planner-check-r<轮数>.json} 留档。规划质量以冻结评测集 agent-behavior-v1 的结果为准。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "retouch.worker.enabled=false")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "retouch.real-planner", matches = "true")
@SuppressWarnings({"unchecked", "rawtypes"})
class RealPlannerCheck {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Value("${retouch.planner.follow-up-rounds:0}")
    int followUpRounds;

    private record Case(String goal, String status, List<String> tools) {
    }

    /** 单步直接执行；多步停在 queued 且工具都在；无关请求不出计划。 */
    private static final List<Case> CASES = List.of(
            new Case("把图片左右镜像一下", "succeeded", List.of("flip_layer")),
            new Case("先上下翻转，然后把透明度改成 60%", "queued", List.of("flip_layer", "set_layer_opacity")),
            new Case("顺时针旋转 90 度，再把亮度调高一点", "queued", List.of("rotate_layer", "adjust_image")),
            new Case("裁成 1:1，然后对比度加强一些", "queued", List.of("crop_canvas", "adjust_image")),
            new Case("帮我写一首关于春天的诗", "succeeded", List.of()));

    @Test
    void plansWithTheRealModel() throws Exception {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(160, 120, false), "check.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        String messages = "/api/sessions/" + session + "/messages";

        // 先把每条的结果都记下来再断言，失败时也能看到模型实际给了什么
        List<Map<String, Object>> report = new ArrayList<>();
        List<Map> turns = new ArrayList<>();
        for (Case check : CASES) {
            Map turn = send(client, messages, check, report);
            turns.add(turn);
            if ("queued".equals(turn.get("status"))) {
                client.post(messages + "/" + turn.get("id") + "/cancel", Map.of());
            }
        }
        Path out = Path.of("target", "real-planner-check-r" + followUpRounds + ".json");
        Files.writeString(out, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "checked_at", Instant.now().toString(), "follow_up_rounds", followUpRounds, "cases", report)));
        System.out.println("真实规划模型检查记录写入 " + out.toAbsolutePath());
        report.forEach(entry -> System.out.println(entry));

        var soft = new SoftAssertions();
        for (int i = 0; i < CASES.size(); i++) {
            Case check = CASES.get(i);
            Map turn = turns.get(i);
            Map<String, Object> entry = report.get(i);
            soft.assertThat(entry.get("http_status")).as("%s：HTTP 状态", check.goal()).isEqualTo(201);
            soft.assertThat(turn.get("error")).as("%s：规划失败", check.goal()).isNull();
            soft.assertThat(entry.get("prompt_tokens")).as("%s：没有用量", check.goal()).isNotNull();
            soft.assertThat(turn.get("status")).as("%s：状态", check.goal()).isEqualTo(check.status());
            soft.assertThat(tools(turn)).as("%s：工具", check.goal()).containsExactlyInAnyOrderElementsOf(check.tools());
            if (!"succeeded".equals(check.status()) || check.tools().isEmpty()) {
                soft.assertThat(runsOf(turn)).as("%s：确认前不应派发", check.goal()).isZero();
            }
        }
        soft.assertAll();
    }

    private Map send(ApiClient client, String messages, Case check, List<Map<String, Object>> report) {
        var response = client.post(messages, Map.of("text", check.goal()));
        Map turn = response.getBody() == null ? Map.of() : response.getBody();
        Map trace = turn.get("id") == null ? Map.of() : client.get(messages + "/" + turn.get("id") + "/trace").getBody();

        var entry = new LinkedHashMap<String, Object>();
        entry.put("goal", check.goal());
        entry.put("expected_tools", check.tools());
        entry.put("http_status", response.getStatusCode().value());
        entry.put("status", turn.get("status"));
        entry.put("tools", turn.get("steps") == null ? List.of() : tools(turn));
        entry.put("reply", turn.get("reply"));
        entry.put("error", turn.get("error"));
        for (String key : List.of("model", "elapsed_ms", "rounds", "prompt_tokens", "completion_tokens",
                "finish_reason", "tool_calls", "text", "rejected", "failure")) {
            entry.put(key, trace.get(key));
        }
        report.add(entry);
        return turn;
    }

    private static List<Object> tools(Map turn) {
        return ((List<Map>) turn.get("steps")).stream().map(step -> step.get("tool")).toList();
    }

    private int runsOf(Map turn) {
        return jdbc.sql("select count(*) from tool_runs where turn_id = ?")
                .param(UUID.fromString((String) turn.get("id"))).query(Integer.class).single();
    }
}
