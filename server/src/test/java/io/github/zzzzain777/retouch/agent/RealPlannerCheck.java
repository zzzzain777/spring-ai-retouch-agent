package io.github.zzzzain777.retouch.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 默认不运行，需显式打开：{@code ./mvnw test -Dtest=RealPlannerCheck -Dretouch.real-planner=true}。
 * <p>
 * 只断言集成是否打通（请求成功、拿到结构化计划、多步计划确认前不派发）；模型选了哪些工具写进
 * {@code target/real-planner-check.json} 留档，规划质量以冻结评测集 agent-behavior-v1 的结果为准。
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

    @Test
    void plansWithTheRealModel() throws Exception {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(160, 120, false), "check.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        String messages = "/api/sessions/" + session + "/messages";

        List<Map<String, Object>> report = new ArrayList<>();
        Map single = send(client, messages, "把图片左右镜像一下", report);
        Map multi = send(client, messages, "先上下翻转，然后把透明度改成 60%", report);
        Map refuse = send(client, messages, "帮我写一首关于春天的诗", report);

        // 单步：模型返回了结构化的工具调用，经校验后直接执行
        assertThat(single.get("status")).isEqualTo("succeeded");
        assertThat(tools(single)).containsExactly("flip_layer");
        // 多步：停在 queued，确认前没有任何任务
        assertThat(multi.get("status")).isEqualTo("queued");
        assertThat(tools(multi)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(runsOf(multi)).isZero();
        assertThat(client.post(messages + "/" + multi.get("id") + "/cancel", Map.of()).getBody())
                .containsEntry("status", "canceled");
        // 无关请求：不产生计划
        assertThat(tools(refuse)).isEmpty();
        assertThat(runsOf(refuse)).isZero();

        Path out = Path.of("target", "real-planner-check.json");
        Files.writeString(out, json.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("checked_at", Instant.now().toString(), "cases", report)));
        System.out.println("真实规划模型检查通过，记录写入 " + out.toAbsolutePath());
        report.forEach(entry -> System.out.println(entry));
    }

    private Map send(ApiClient client, String messages, String text, List<Map<String, Object>> report) {
        var response = client.post(messages, Map.of("text", text));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map turn = response.getBody();
        Map trace = client.get(messages + "/" + turn.get("id") + "/trace").getBody();
        assertThat(turn.get("error")).as("规划失败：%s", trace).isNull();
        assertThat(((Number) trace.get("prompt_tokens")).intValue()).isPositive();

        var entry = new LinkedHashMap<String, Object>();
        entry.put("goal", text);
        entry.put("status", turn.get("status"));
        entry.put("tools", tools(turn));
        entry.put("reply", turn.get("reply"));
        entry.put("model", trace.get("model"));
        entry.put("elapsed_ms", trace.get("elapsed_ms"));
        entry.put("prompt_tokens", trace.get("prompt_tokens"));
        entry.put("completion_tokens", trace.get("completion_tokens"));
        entry.put("tool_calls", trace.get("tool_calls"));
        entry.put("rejected", trace.get("rejected"));
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
