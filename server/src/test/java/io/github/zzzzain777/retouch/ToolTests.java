package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.openai.api-key=placeholder-for-context-test")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class ToolTests {

    @Autowired
    TestRestTemplate http;

    private record Fixture(ApiClient client, String sessionId, String assetId) {
        ResponseEntity<Map> tool(String tool, Map<String, Object> params) {
            return client.post("/api/sessions/" + sessionId + "/tools", Map.of("tool", tool, "params", params));
        }
    }

    private Fixture fixture(int width, int height) {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(width, height, false), "a.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        return new Fixture(client, session, asset);
    }

    private static Map base(Map body) {
        return ((List<Map>) ((Map) body.get("document")).get("layers")).getFirst();
    }

    @Test
    void listsToolSpecsFromTheSingleRegistry() {
        var tools = ApiClient.registered(http).exchange(HttpMethod.GET, "/api/tools", null, List.class).getBody();
        assertThat(tools.stream().map(t -> ((Map) t).get("name")).toList()).contains("flip_layer", "crop_canvas",
                "move_layer", "scale_layer", "set_layer_text");
        Map scale = (Map) tools.stream().filter(t -> ((Map) t).get("name").equals("scale_layer")).findFirst()
                .orElseThrow();
        assertThat(scale).containsEntry("execution", "document").containsEntry("session_required", true);
        assertThat((List) scale.get("agent_hidden")).containsExactlyInAnyOrder("x", "y");
    }

    @Test
    void flipRunsSynchronouslyAndIsUndoable() {
        var f = fixture(400, 300);
        var response = f.tool("flip_layer", Map.of("direction", "horizontal"));
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        var run = (Map) response.getBody().get("run");
        assertThat(run).containsEntry("tool", "flip_layer").containsEntry("status", "succeeded")
                .containsEntry("progress", 100).containsEntry("stage", "已完成").containsEntry("error", null);
        var session = (Map) response.getBody().get("session");
        assertThat((Map) base(session).get("transform")).containsEntry("scale_x", -1.0);
        assertThat(session).containsEntry("revision", 2).containsEntry("history_seq", 2);

        var history = f.client().exchange(HttpMethod.GET, "/api/sessions/" + f.sessionId() + "/history", null,
                List.class).getBody();
        var latest = (Map) history.getFirst();
        assertThat(latest).containsEntry("action", "flip_layer");
        assertThat((Map) latest.get("params")).containsEntry("direction", "horizontal").containsEntry("layer_id", null)
                .containsKey("before");

        var undone = f.client().post("/api/sessions/" + f.sessionId() + "/undo", null).getBody();
        assertThat((Map) base(undone).get("transform")).containsEntry("scale_x", 1.0);

        var fetched = f.client().get("/api/runs/" + run.get("id"));
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody()).containsEntry("status", "succeeded").containsEntry("candidates", List.of());
    }

    @Test
    void cropByRatioTranslatesLayers() {
        var f = fixture(400, 300);
        var session = (Map) f.tool("crop_canvas", Map.of("ratio", "1:1")).getBody().get("session");
        assertThat((Map) session.get("document")).containsEntry("width", 300).containsEntry("height", 300);
        assertThat((Map) base(session).get("transform")).containsEntry("x", -50.0).containsEntry("y", 0.0);

        var tiny = (Map) f.tool("crop_canvas", Map.of("rect", Map.of("x", 0, "y", 0, "width", 0.01, "height", 0.5)))
                .getBody().get("run");
        assertThat(tiny).containsEntry("status", "failed")
                .containsEntry("error", "裁剪区域过小，最短边需不小于 32 像素");
    }

    @Test
    void rejectsUnknownToolsAndInvalidParams() {
        var f = fixture(200, 200);
        var unknown = f.tool("teleport_subject", Map.of());
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody()).containsEntry("detail", "未注册的工具：teleport_subject");

        assertThat((String) f.tool("set_layer_opacity", Map.of("opacity", 2)).getBody().get("detail"))
                .startsWith("opacity：");
        assertThat(f.tool("flip_layer", Map.of("direction", "diagonal")).getBody())
                .containsEntry("detail", "direction：取值类型不正确");
        assertThat(f.tool("scale_layer", Map.of()).getBody()).containsEntry("detail", "参数：需要相对倍率或绝对缩放");
        assertThat(f.tool("crop_canvas", Map.of("ratio", "1:1", "rect",
                Map.of("x", 0, "y", 0, "width", 0.5, "height", 0.5))).getBody())
                .containsEntry("detail", "参数：裁剪需要比例或裁剪框之一");
        assertThat(f.tool("crop_canvas", Map.of("rect", Map.of("x", 0.8, "y", 0, "width", 0.5, "height", 0.5)))
                .getBody()).containsEntry("detail", "rect：裁剪框超出画布");
        assertThat(f.tool("set_layer_text", Map.of("text", "hi", "fill", "red")).getStatusCode().value())
                .isEqualTo(422);
        assertThat(f.client().post("/api/sessions/" + f.sessionId() + "/tools", Map.of("params", Map.of()))
                .getStatusCode().value()).isEqualTo(422);

        // 参数非法时既不建任务也不改会话
        assertThat(f.client().get("/api/sessions/" + f.sessionId()).getBody()).containsEntry("history_seq", 1);
    }

    @Test
    void toolFailuresBecomeFailedRunsWithoutTouchingTheCanvas() {
        var f = fixture(200, 200);
        var noText = f.tool("set_layer_text", Map.of("text", "新品上市"));
        assertThat(noText.getStatusCode().value()).isEqualTo(202);
        assertThat((Map) noText.getBody().get("run")).containsEntry("status", "failed")
                .containsEntry("error", "没有可编辑的文字图层");
        var missing = (Map) f.tool("flip_layer", Map.of("direction", "vertical", "layer_id", "物体9")).getBody()
                .get("run");
        assertThat(missing).containsEntry("status", "failed").containsEntry("error", "图层不存在：物体9");
        assertThat(f.client().get("/api/sessions/" + f.sessionId()).getBody()).containsEntry("history_seq", 1)
                .containsEntry("revision", 1);
    }

    @Test
    void toolsAndRunsAreScopedToTheOwner() {
        var f = fixture(200, 200);
        String runId = (String) ((Map) f.tool("flip_layer", Map.of("direction", "vertical")).getBody().get("run"))
                .get("id");
        var bob = ApiClient.registered(http);
        var hijack = bob.post("/api/sessions/" + f.sessionId() + "/tools",
                Map.of("tool", "flip_layer", "params", Map.of("direction", "horizontal")));
        assertThat(hijack.getStatusCode().value()).isEqualTo(404);
        assertThat(hijack.getBody()).containsEntry("detail", "会话不存在");
        assertThat(bob.get("/api/runs/" + runId).getBody()).containsEntry("detail", "任务不存在");
        assertThat((Map) base(f.client().get("/api/sessions/" + f.sessionId()).getBody()).get("transform"))
                .containsEntry("scale_x", 1.0).containsEntry("scale_y", -1.0);
    }
}
