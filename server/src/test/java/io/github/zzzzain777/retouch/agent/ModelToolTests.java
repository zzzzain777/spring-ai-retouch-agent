package io.github.zzzzain777.retouch.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.zzzzain777.retouch.ApiClient;
import io.github.zzzzain777.retouch.FakeDashScopeConfiguration;
import io.github.zzzzain777.retouch.FakeDashScopeServer;
import io.github.zzzzain777.retouch.ImageServiceTestConfiguration;
import io.github.zzzzain777.retouch.TestcontainersConfiguration;

/**
 * B9 的 12 个图像工具走完整链路：HTTP → Java 校验与排队 → Worker → 真实 Python 图像服务 → 对象存储 → Java 登记素材、改文档。
 * 图像模型用本地假 DashScope（{@link FakeDashScopeServer}），只验证请求与流程，不代表真实模型效果；规划模型用脚本替身。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.worker.poll-interval=200ms"})
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, ImageServiceTestConfiguration.class, FakeDashScopeConfiguration.class,
        AgentTests.ScriptedModelConfiguration.class})
@EnabledIf("io.github.zzzzain777.retouch.ImageServiceProcess#available")
@SuppressWarnings({"unchecked", "rawtypes"})
class ModelToolTests {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FakeDashScopeServer dashscope;

    @Autowired
    ScriptedChatModel model;

    private record Fixture(ApiClient client, String sessionId, String assetId) {
        String path(String suffix) {
            return "/api/sessions/" + sessionId + suffix;
        }
    }

    @BeforeEach
    void reset() {
        dashscope.reset();
        model.reset();
    }

    private Fixture fixture() {
        var client = ApiClient.registered(http);
        // 黑底中间一块蓝色：四角取色能抠出中间的方块
        String asset = (String) client.upload(ApiClient.png(120, 80, false), "p.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        return new Fixture(client, session, asset);
    }

    private static Map invoke(Fixture f, String tool, Map<String, Object> params) throws InterruptedException {
        var response = f.client().post(f.path("/tools"), Map.of("tool", tool, "params", params));
        assertThat(response.getStatusCode().value()).as("%s：%s", tool, response.getBody()).isEqualTo(202);
        return settle(f.client(), (String) ((Map) response.getBody().get("run")).get("id"));
    }

    private static Map settle(ApiClient client, String runId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (true) {
            Map run = client.get("/api/runs/" + runId).getBody();
            if (List.of("succeeded", "failed", "canceled").contains(run.get("status"))
                    || Instant.now().isAfter(deadline)) {
                return run;
            }
            Thread.sleep(200);
        }
    }

    private static Map session(Fixture f) {
        return f.client().get(f.path("")).getBody();
    }

    private static List<Map> layers(Map session) {
        return (List<Map>) ((Map) session.get("document")).get("layers");
    }

    private Map asset(Fixture f, Object id) {
        return f.client().get("/api/assets/" + id).getBody();
    }

    private void select(Fixture f, int revision) {
        var stroke = List.of(Map.of("x", 0.35, "y", 0.35), Map.of("x", 0.65, "y", 0.35), Map.of("x", 0.65, "y", 0.65),
                Map.of("x", 0.35, "y", 0.65));
        var response = f.client().post(f.path("/selection"), Map.of("revision", revision, "strokes", List.of(stroke),
                "radius", 0.02));
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
    }

    @Test
    void localToolsNeedNoModel() throws Exception {
        var f = fixture();
        Map removed = invoke(f, "remove_background", Map.of());
        assertThat(removed).containsEntry("status", "succeeded");
        Map s = session(f);
        Object subject = layers(s).getFirst().get("asset_id");
        assertThat(asset(f, subject)).containsEntry("kind", "subject").containsEntry("has_alpha", true);
        assertThat(s).containsEntry("revision", 2).containsEntry("current_asset_id", subject);

        Map delivery = invoke(f, "prepare_delivery_sizes", Map.of());
        assertThat(delivery).containsEntry("status", "succeeded");
        var variants = (List<Map>) ((Map) delivery.get("result")).get("variants");
        assertThat(variants).extracting(v -> v.get("ratio")).containsExactly("1:1", "4:5", "9:16");
        assertThat(asset(f, variants.get(2).get("asset_id"))).containsEntry("kind", "export")
                .containsEntry("width", 1080).containsEntry("height", 1920);
        // 只进图片墙：画布与修订号不变
        Map after = session(f);
        assertThat(after).containsEntry("revision", 2).containsEntry("current_asset_id", subject);
        assertThat(dashscope.received()).isEmpty();
    }

    @Test
    void wholeImageModelToolsUseOurPromptsAndStoreResults() throws Exception {
        var f = fixture();
        Map replaced = invoke(f, "replace_background", Map.of("prompt", "原木桌面"));
        assertThat(replaced).as("%s", replaced).containsEntry("status", "succeeded");
        var sent = dashscope.received().getFirst();
        assertThat(sent.path()).isEqualTo("/api/v1/services/aigc/multimodal-generation/generation");
        assertThat(sent.authorization()).isEqualTo("Bearer " + FakeDashScopeServer.KEY);
        assertThat(sent.body().path("model").asString()).isEqualTo("qwen-image-edit-max");
        assertThat(sent.body().path("input").path("messages").get(0).path("content").get(1).path("text").asString())
                .startsWith("保留商品主体").endsWith("原木桌面");
        Map s = session(f);
        assertThat(s).containsEntry("revision", 2);
        assertThat(asset(f, layers(s).getFirst().get("asset_id"))).containsEntry("kind", "generated");

        // 多张候选只上墙，不改画布
        Map candidates = invoke(f, "replace_background", Map.of("prompt", "海边", "count", 2));
        assertThat((List) ((Map) candidates.get("result")).get("asset_ids")).hasSize(2);
        assertThat(session(f)).containsEntry("revision", 2);

        // 扩图：16:9 刚好包住 120×80 是 142×80，再按编辑接口的边长限制放大
        Map expanded = invoke(f, "expand_canvas", Map.of("ratio", "16:9"));
        assertThat(expanded).containsEntry("status", "succeeded");
        assertThat(dashscope.received().getLast().body().path("parameters").path("size").asString())
                .isEqualTo("908*512");
        Map afterExpand = session(f);
        assertThat(afterExpand).containsEntry("revision", 3);
        assertThat((Map) afterExpand.get("document")).containsEntry("width", 908).containsEntry("height", 512);

        Map upscaled = invoke(f, "upscale_image", Map.of("scale", 2));
        assertThat(upscaled).containsEntry("status", "succeeded");
        assertThat(session(f)).containsEntry("revision", 4);

        Map marketing = invoke(f, "generate_marketing", Map.of("kind", "poster", "caption", "双十一", "ratio", "4:5"));
        assertThat(marketing).containsEntry("status", "succeeded");
        var variant = ((List<Map>) ((Map) marketing.get("result")).get("variants")).getFirst();
        assertThat(variant).containsEntry("kind", "poster").containsEntry("ratio", "4:5");
        assertThat(asset(f, variant.get("asset_id"))).containsEntry("kind", "marketing").containsEntry("width", 1080)
                .containsEntry("height", 1350);
        assertThat(session(f)).containsEntry("revision", 4);
    }

    @Test
    void regionToolsUseTheSelectionAndClearIt() throws Exception {
        var f = fixture();
        select(f, 1);
        Map erased = invoke(f, "erase_region", Map.of());
        assertThat(erased).as("%s", erased).containsEntry("status", "succeeded");
        assertThat(dashscope.received().getLast().body().path("input").path("messages").get(0).path("content").get(1)
                .path("text").asString()).startsWith("去掉选区里的物体");
        assertThat(f.client().exchange(HttpMethod.GET, f.path("/selection"), null, String.class).getBody())
                .isEqualTo("null");

        // 没有选区：改整层，提示词换成整层版本
        Map replaced = invoke(f, "replace_region", Map.of("prompt", "换成红色皮革"));
        assertThat(replaced).containsEntry("status", "succeeded");
        assertThat(dashscope.received().getLast().body().path("input").path("messages").get(0).path("content").get(1)
                .path("text").asString()).startsWith("换成红色皮革。");

        // 计划里记的修订号过期：不调模型
        int before = dashscope.received().size();
        Map stale = invoke(f, "replace_region", Map.of("prompt", "换成蓝色", "revision", 1));
        assertThat(stale).containsEntry("status", "failed").containsEntry("error", "选区已过期，请重新选择");
        assertThat(dashscope.received()).hasSize(before);
    }

    @Test
    void splitThenPromoteRestructuresTheLayers() throws Exception {
        var f = fixture();
        Map split = invoke(f, "split_layers", Map.of());
        assertThat(split).as("%s", split).containsEntry("status", "succeeded");
        Map s = session(f);
        assertThat(layers(s)).extracting(l -> l.get("id")).containsExactly("background", "subject");
        assertThat(layers(s).getFirst()).containsEntry("locked", true);
        assertThat(asset(f, layers(s).get(1).get("asset_id"))).containsEntry("kind", "subject");
        int revision = ((Number) s.get("revision")).intValue();

        Map again = invoke(f, "split_layers", Map.of());
        assertThat(again).containsEntry("status", "succeeded");
        assertThat(session(f)).containsEntry("revision", revision);

        Map text = invoke(f, "split_layers", Map.of("include_text", true));
        assertThat(text).containsEntry("status", "succeeded");

        Map missing = invoke(f, "promote_object_to_layer", Map.of());
        assertThat(missing).containsEntry("status", "failed").containsEntry("error", "请先点选或涂抹要拆出的物体");

        select(f, revision);
        Map promoted = invoke(f, "promote_object_to_layer", Map.of("name", "杯子"));
        assertThat(promoted).as("%s", promoted).containsEntry("status", "succeeded");
        var after = layers(session(f));
        assertThat(after).extracting(l -> l.get("id")).startsWith("background", "subject");
        assertThat(after).hasSize(3);
        assertThat(after.getLast()).containsEntry("name", "杯子");
        assertThat((String) after.getLast().get("id")).startsWith("object-");
        assertThat(after.get(1).get("asset_id")).isNotEqualTo(layers(s).get(1).get("asset_id"));
    }

    @Test
    void agentPinsTheSelectionIntoRegionSteps() throws Exception {
        var f = fixture();
        select(f, 1);
        model.thenCalls(Map.entry("erase_region", Map.of()));
        String messages = f.path("/messages");
        Map turn = f.client().post(messages, Map.of("text", "把选中的东西去掉")).getBody();
        assertThat(turn.get("status")).isIn("running", "succeeded");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        Map last;
        do {
            Thread.sleep(200);
            var turns = (List<Map>) (List) f.client().exchange(HttpMethod.GET, messages, null, List.class).getBody();
            last = turns.getLast();
        } while ("running".equals(last.get("status")) && Instant.now().isBefore(deadline));
        assertThat(last).as("%s", last).containsEntry("status", "succeeded");
        String params = jdbc.sql("select params::text from tool_runs where turn_id = ?::uuid")
                .param(turn.get("id")).query(String.class).single();
        assertThat(params).contains("\"mask_asset_id\": \"");
    }

    @Test
    void generationsAndBatches() throws Exception {
        var f = fixture();
        var created = f.client().post("/api/generations", Map.of("prompt", "白底运动鞋", "count", 2));
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        Map generated = settle(f.client(), (String) created.getBody().get("id"));
        assertThat(generated).as("%s", generated).containsEntry("status", "succeeded");
        var candidates = (List<Map>) generated.get("candidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.getFirst()).containsEntry("source", "generate").containsEntry("width", 1080);
        assertThat(dashscope.received().getLast().body().path("model").asString()).isEqualTo("qwen-image-3.0-pro");

        assertThat(f.client().post("/api/generations", Map.of("prompt", "x", "reference_asset_ids",
                List.of("00000000-0000-4000-8000-000000000000"))).getStatusCode().value()).isEqualTo(404);

        String other = (String) f.client().upload(ApiClient.png(60, 60, false), "q.png").getBody().get("id");
        var body = Map.of("asset_ids", List.of(f.assetId(), other), "operations",
                List.of(Map.of("tool", "remove_background"),
                        Map.of("tool", "prepare_delivery_sizes", "params", Map.of("ratios", List.of("1:1")))),
                "formats", List.of("png", "jpg"));
        var batch = f.client().post("/api/batches", body);
        assertThat(batch.getStatusCode().value()).as("%s", batch.getBody()).isEqualTo(202);
        String runId = (String) batch.getBody().get("id");
        Map done = settle(f.client(), runId);
        assertThat(done).as("%s", done).containsEntry("status", "succeeded");

        Map detail = f.client().get("/api/batches/" + runId).getBody();
        var items = (List<Map>) detail.get("items");
        assertThat(items).hasSize(2).allSatisfy(item -> assertThat(item).containsEntry("status", "succeeded"));
        var outputs = (List<Map>) items.getFirst().get("outputs");
        assertThat(outputs).extracting(o -> o.get("image_format")).containsExactly("PNG", "JPEG");
        assertThat(outputs).allSatisfy(o -> assertThat(o).containsEntry("kind", "export").containsEntry("width", 1080));

        byte[] zip = f.client().exchange(HttpMethod.GET, "/api/batches/" + runId + "/export", null, byte[].class)
                .getBody();
        var names = new ArrayList<String>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        assertThat(names).containsExactly("images/01-1080x1080.png", "images/02-1080x1080.jpg",
                "images/03-1080x1080.png", "images/04-1080x1080.jpg");
        assertThat(f.client().exchange(HttpMethod.GET, "/api/batches", null, List.class).getBody()).hasSize(1);

        // 校验在建任务之前：素材不存在 404，操作参数不合法 422
        var unknown = Map.of("asset_ids", List.of("00000000-0000-4000-8000-000000000000"), "operations",
                List.of(Map.of("tool", "remove_background")));
        assertThat(f.client().post("/api/batches", unknown).getStatusCode().value()).isEqualTo(404);
        var invalid = Map.of("asset_ids", List.of(f.assetId()), "operations",
                List.of(Map.of("tool", "adjust_image", "params", Map.of("brightness", 5))));
        var rejected = f.client().post("/api/batches", invalid);
        assertThat(rejected.getStatusCode().value()).isEqualTo(422);
        assertThat(rejected.getBody().get("detail")).asString().startsWith("operations.0：brightness");
    }
}
