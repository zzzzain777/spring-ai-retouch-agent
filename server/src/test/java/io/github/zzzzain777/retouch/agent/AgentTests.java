package io.github.zzzzain777.retouch.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.zzzzain777.retouch.ApiClient;
import io.github.zzzzain777.retouch.TestcontainersConfiguration;

/**
 * Agent 主路径集成测试：真实 HTTP、安全过滤链、数据库与工具执行；只把规划模型换成脚本模型。
 * 这里验证的是 Java 侧的计划校验、确认门、取消、重试、幂等与恢复，不是模型的规划质量。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.worker.enabled=false"})
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, AgentTests.ScriptedModelConfiguration.class})
@SuppressWarnings({"unchecked", "rawtypes"})
class AgentTests {

    @TestConfiguration
    static class ScriptedModelConfiguration {
        @Bean
        @Primary
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ScriptedChatModel model;

    @Autowired
    AgentService agent;

    private record Fixture(ApiClient client, String sessionId, String assetId) {

        String messages() {
            return "/api/sessions/" + sessionId + "/messages";
        }

        ResponseEntity<Map> send(String text) {
            return client.post(messages(), Map.of("text", text));
        }

        ResponseEntity<Map> act(Map turn, String action) {
            return client.post(messages() + "/" + turn.get("id") + "/" + action, Map.of());
        }

        Map session() {
            return client.get("/api/sessions/" + sessionId).getBody();
        }

        Map topLayer() {
            var layers = (List<Map>) ((Map) session().get("document")).get("layers");
            return layers.getLast();
        }

        /** 历史接口按 seq 倒序返回，这里转成先后顺序。 */
        List<Map> historyEntries() {
            var entries = new ArrayList<Map>((List<Map>) (List) client.exchange(
                    org.springframework.http.HttpMethod.GET, "/api/sessions/" + sessionId + "/history", null,
                    List.class).getBody());
            entries.sort(java.util.Comparator.comparingInt(entry -> ((Number) entry.get("seq")).intValue()));
            return entries;
        }

        List<String> history() {
            return historyEntries().stream().map(entry -> (String) entry.get("action")).toList();
        }
    }

    @BeforeEach
    void resetScript() {
        model.reset();
    }

    private Fixture fixture() {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(120, 80, false), "a.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        return new Fixture(client, session, asset);
    }

    private static Map.Entry<String, Map<String, Object>> call(String tool, Map<String, Object> params) {
        return Map.entry(tool, params);
    }

    private int runsOf(Map turn) {
        return jdbc.sql("select count(*) from tool_runs where turn_id = ?").param(UUID.fromString((String) turn.get("id")))
                .query(Integer.class).single();
    }

    private static List<Map> steps(Map turn) {
        return (List<Map>) turn.get("steps");
    }

    private static Map transform(Map layer) {
        return (Map) layer.get("transform");
    }

    @Test
    void singleStepRunsImmediatelyWithoutConfirmation() {
        var f = fixture();
        model.thenCalls(call("flip_layer", Map.of("direction", "horizontal")));

        var response = f.send("把画面水平翻转");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map turn = response.getBody();
        assertThat(turn).containsEntry("status", "succeeded").containsEntry("reply", "好，正在水平翻转。")
                .containsEntry("revision", 1).containsEntry("goal", "把画面水平翻转");
        assertThat(steps(turn)).singleElement().satisfies(step -> {
            assertThat(step).containsEntry("id", "s1").containsEntry("tool", "flip_layer")
                    .containsEntry("label", "水平翻转").containsEntry("status", "succeeded");
            assertThat(step.get("run_id")).isNotNull();
            assertThat(step.get("depends_on")).isEqualTo(List.of());
        });
        assertThat(((Number) transform(f.topLayer()).get("scale_x")).doubleValue()).isLessThan(0);
        assertThat(f.history()).last().isEqualTo("flip_layer");
    }

    @Test
    void multiStepPlanWaitsForConfirmationThenRunsInOrder() {
        var f = fixture();
        model.thenCalls(call("flip_layer", Map.of("direction", "horizontal")),
                call("flip_layer", Map.of("direction", "vertical")));

        Map turn = f.send("先水平翻转，再垂直翻转").getBody();
        assertThat(turn).containsEntry("status", "queued")
                .containsEntry("reply", "将按以下步骤执行：水平翻转、垂直翻转。确认后开始。");
        assertThat(steps(turn)).extracting(step -> step.get("status")).containsOnly("pending");
        assertThat(steps(turn).get(1).get("depends_on")).isEqualTo(List.of("s1"));
        assertThat(runsOf(turn)).isZero();
        assertThat(f.session()).containsEntry("revision", 1);

        var confirmed = f.act(turn, "confirm");
        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(confirmed.getBody()).containsEntry("status", "succeeded");
        assertThat(steps(confirmed.getBody())).extracting(step -> step.get("status"))
                .containsExactly("succeeded", "succeeded");
        var t = transform(f.topLayer());
        assertThat(((Number) t.get("scale_x")).doubleValue()).isLessThan(0);
        assertThat(((Number) t.get("scale_y")).doubleValue()).isLessThan(0);
        assertThat(f.history()).containsSubsequence("flip_layer", "flip_layer");

        var again = f.act(turn, "confirm");
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).containsEntry("detail", "当前没有待确认的步骤");
        assertThat(runsOf(turn)).isEqualTo(2);
    }

    @Test
    void cancelBeforeConfirmHasNoSideEffects() {
        var f = fixture();
        model.thenCalls(call("flip_layer", Map.of("direction", "horizontal")),
                call("set_layer_opacity", Map.of("opacity", 0.5)));
        Map turn = f.send("翻转并半透明").getBody();

        var canceled = f.act(turn, "cancel");
        assertThat(canceled.getStatusCode().value()).isEqualTo(200);
        assertThat(canceled.getBody()).containsEntry("status", "canceled");
        assertThat(steps(canceled.getBody())).extracting(step -> step.get("status")).containsOnly("canceled");
        assertThat(runsOf(turn)).isZero();
        assertThat(f.session()).containsEntry("revision", 1);
        assertThat(transform(f.topLayer())).containsEntry("scale_x", 1.0);

        assertThat(f.act(turn, "cancel").getBody()).containsEntry("detail", "当前没有可取消的步骤");
        assertThat(f.act(turn, "confirm").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void stalePlanIsNotExecutedAfterTheCanvasChanged() {
        var f = fixture();
        model.thenCalls(call("flip_layer", Map.of("direction", "horizontal")),
                call("flip_layer", Map.of("direction", "vertical")));
        Map turn = f.send("先水平翻转，再垂直翻转").getBody();

        // 等待确认期间手动改了画布
        f.client().post("/api/sessions/" + f.sessionId() + "/tools",
                Map.of("tool", "rotate_layer", "params", Map.of("angle", 90)));
        assertThat(f.session()).containsEntry("revision", 2);

        var confirmed = f.act(turn, "confirm");
        assertThat(confirmed.getStatusCode().value()).isEqualTo(409);
        assertThat(confirmed.getBody()).containsEntry("detail", "画布在等待确认期间已被修改，请重新发送指令");
        assertThat(runsOf(turn)).isZero();
        assertThat(f.history()).doesNotContain("flip_layer");
        assertThat(f.act(turn, "cancel").getBody()).containsEntry("status", "canceled");
    }

    @Test
    void invalidProposalsBecomeAReplyAndNothingRuns() {
        var f = fixture();
        model.thenCalls(call("delete_all_assets", Map.of()));
        Map unknown = f.send("删除我的全部素材").getBody();
        assertThat(unknown).containsEntry("status", "succeeded")
                .containsEntry("reply", "这一步暂时执行不了：未注册的工具：delete_all_assets");
        assertThat(steps(unknown)).isEmpty();
        assertThat(f.client().get(f.messages() + "/" + unknown.get("id") + "/trace").getBody())
                .containsEntry("rejected", "未注册的工具：delete_all_assets");

        model.thenCalls(call("set_layer_opacity", Map.of("opacity", 5)));
        assertThat((String) f.send("透明度 500%").getBody().get("reply")).startsWith("这一步暂时执行不了：opacity：");

        model.thenRawCall("flip_layer", "{not json");
        assertThat(f.send("翻转").getBody()).containsEntry("reply", "这一步暂时执行不了：工具参数格式不正确");

        List<Map.Entry<String, Map<String, Object>>> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(call("flip_layer", Map.of("direction", "horizontal")));
        }
        model.thenCalls(nine.toArray(Map.Entry[]::new));
        assertThat(f.send("翻转九次").getBody()).containsEntry("reply", "这一步暂时执行不了：计划超过 8 步，请拆成多次说明");

        assertThat(f.session()).containsEntry("revision", 1);
        assertThat(f.history()).containsExactly("create_session");
    }

    @Test
    void hiddenParametersFromTheModelAreDropped() {
        var f = fixture();
        model.thenCalls(call("scale_layer", Map.of("factor", 2, "x", 999)));
        Map turn = f.send("放大两倍").getBody();
        assertThat(turn).containsEntry("status", "succeeded");
        var params = (Map) f.historyEntries().getLast().get("params");
        assertThat(params.get("factor")).isEqualTo(2.0);
        assertThat(params.get("x")).isNull();
        assertThat(((Number) transform(f.topLayer()).get("x")).doubleValue()).isNotEqualTo(999.0);
    }

    @Test
    void textOnlyAnswerHasNoPlanAndNothingToConfirm() {
        var f = fixture();
        model.thenText("这个需求超出了修图范围。你可以试试换个说法。");
        Map turn = f.send("帮我订一张机票").getBody();
        assertThat(turn).containsEntry("status", "succeeded").containsEntry("reply", "这个需求超出了修图范围。");
        assertThat(steps(turn)).isEmpty();

        assertThat(f.act(turn, "confirm").getBody()).containsEntry("detail", "当前没有待确认的步骤");
        assertThat(f.act(turn, "retry").getBody()).containsEntry("detail", "没有失败的步骤可重试");
        assertThat(f.act(turn, "cancel").getBody()).containsEntry("detail", "当前没有可取消的步骤");
    }

    @Test
    void plannerFailureFailsTheTurnWithoutSideEffects() {
        var f = fixture();
        model.thenFail(new IllegalStateException("连接被重置"));
        var response = f.send("把图片调亮");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsEntry("status", "failed").containsEntry("error", "规划失败，请重试");
        assertThat(steps(response.getBody())).isEmpty();
        assertThat(f.history()).containsExactly("create_session");
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameTurnAndPlansOnce() {
        var f = fixture();
        model.thenCalls(call("flip_layer", Map.of("direction", "horizontal")));
        var first = f.client().post(f.messages(), Map.of("text", "水平翻转"), Map.of("Idempotency-Key", "k-1"));
        var second = f.client().post(f.messages(), Map.of("text", "水平翻转"), Map.of("Idempotency-Key", "k-1"));

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(model.received()).hasSize(1);
        assertThat(f.history()).containsExactly("create_session", "flip_layer");
        assertThat((List) f.client().exchange(org.springframework.http.HttpMethod.GET, f.messages(), null, List.class)
                .getBody()).hasSize(1);

        var tooLong = f.client().post(f.messages(), Map.of("text", "翻转"), Map.of("Idempotency-Key", "k".repeat(65)));
        assertThat(tooLong.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void emptyMessageIsRejected() {
        var f = fixture();
        var response = f.send("   ");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("detail", "text：指令不能为空");
        assertThat(model.received()).isEmpty();
    }

    @Test
    void failedStepBlocksTheRestAndRetryRunsItAgain() {
        var f = fixture();
        // 裁剪框合法，但只有 12×8 像素，执行时才会失败
        model.thenCalls(call("crop_canvas", Map.of("rect", Map.of("x", 0, "y", 0, "width", 0.1, "height", 0.1))),
                call("flip_layer", Map.of("direction", "horizontal")));
        Map turn = f.send("裁一小块再翻转").getBody();

        Map confirmed = f.act(turn, "confirm").getBody();
        assertThat(confirmed).containsEntry("status", "failed");
        assertThat((String) confirmed.get("error")).contains("裁剪区域过小");
        assertThat(steps(confirmed)).extracting(step -> step.get("status")).containsExactly("failed", "pending");
        Object firstRun = steps(confirmed).getFirst().get("run_id");

        Map retried = f.act(turn, "retry").getBody();
        assertThat(retried).containsEntry("status", "failed");
        assertThat(steps(retried).getFirst().get("run_id")).isNotNull().isNotEqualTo(firstRun);
        assertThat(jdbc.sql("select step_attempt from tool_runs where turn_id = ? order by step_attempt")
                .param(UUID.fromString((String) turn.get("id"))).query(Integer.class).list()).containsExactly(1, 2);
        assertThat(f.history()).doesNotContain("flip_layer");
    }

    @Test
    void otherUsersAndOtherSessionsCannotTouchATurn() {
        var a = fixture();
        model.thenCalls(call("flip_layer", Map.of("direction", "horizontal")),
                call("flip_layer", Map.of("direction", "vertical")));
        Map turn = a.send("先水平翻转，再垂直翻转").getBody();

        var b = new Fixture(ApiClient.registered(http), a.sessionId(), a.assetId());
        for (String action : List.of("confirm", "cancel", "retry")) {
            var response = b.act(turn, action);
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).containsEntry("detail", "会话不存在");
        }
        assertThat(b.client().get(a.messages()).getStatusCode().value()).isEqualTo(404);

        String otherSession = (String) a.client().post("/api/sessions", Map.of("current_asset_id", a.assetId()))
                .getBody().get("id");
        var other = new Fixture(a.client(), otherSession, a.assetId());
        assertThat(other.act(turn, "confirm").getBody()).containsEntry("detail", "对话不存在");

        var anonymous = new ApiClient(http).post(a.messages() + "/" + turn.get("id") + "/confirm", Map.of());
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        assertThat(runsOf(turn)).isZero();
        assertThat(transform(a.topLayer())).containsEntry("scale_x", 1.0);
    }

    @Test
    void lostPlanUpdateIsReconciledFromRunsWithoutRunningStepsTwice() {
        var f = fixture();
        model.thenCalls(call("flip_layer", Map.of("direction", "horizontal")),
                call("flip_layer", Map.of("direction", "vertical")));
        Map turn = f.send("先水平翻转，再垂直翻转").getBody();
        f.act(turn, "confirm");
        UUID turnId = UUID.fromString((String) turn.get("id"));
        assertThat(runsOf(turn)).isEqualTo(2);

        // 模拟“任务已提交、计划未更新”时进程崩溃：计划回到确认后、派发前的样子
        String stale = jdbc.sql("select plan::text from agent_turns where id = ?").param(turnId).query(String.class)
                .single().replaceAll("\"run_id\":\"[^\"]+\"", "\"run_id\":null")
                .replace("\"status\":\"succeeded\"", "\"status\":\"pending\"").replace("\"attempt\":1", "\"attempt\":0");
        jdbc.sql("update agent_turns set plan = cast(? as jsonb), status = 'running' where id = ?").param(stale)
                .param(turnId).update();

        agent.advance(turnId);

        Map after = ((List<Map>) (List) f.client().exchange(org.springframework.http.HttpMethod.GET, f.messages(),
                null, List.class).getBody()).getFirst();
        assertThat(after).containsEntry("status", "succeeded");
        assertThat(steps(after)).extracting(step -> step.get("status")).containsExactly("succeeded", "succeeded");
        assertThat(runsOf(turn)).isEqualTo(2);
        assertThat(f.history().stream().filter("flip_layer"::equals).count()).isEqualTo(2);
    }
}
