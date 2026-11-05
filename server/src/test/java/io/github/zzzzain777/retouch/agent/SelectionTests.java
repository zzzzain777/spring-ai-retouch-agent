package io.github.zzzzain777.retouch.agent;

import static org.assertj.core.api.Assertions.assertThat;

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

/** 选区：经真实 Python 图像服务栅格化，存 Redis 并绑定修订号；规划时作为画布事实交给模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.worker.enabled=false"})
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, ImageServiceTestConfiguration.class,
        AgentTests.ScriptedModelConfiguration.class})
@EnabledIf("io.github.zzzzain777.retouch.ImageServiceProcess#available")
@SuppressWarnings({"unchecked", "rawtypes"})
class SelectionTests {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ScriptedChatModel model;

    private record Fixture(ApiClient client, String sessionId) {
        String path() {
            return "/api/sessions/" + sessionId + "/selection";
        }

        String getRaw() {
            return client.exchange(HttpMethod.GET, path(), null, String.class).getBody();
        }
    }

    private Fixture fixture() {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(120, 80, false), "a.png").getBody().get("id");
        String session = (String) client.post("/api/sessions", Map.of("current_asset_id", asset)).getBody().get("id");
        return new Fixture(client, session);
    }

    private static Map stroke(int revision, double x) {
        return Map.of("revision", revision, "strokes", List.of(List.of(Map.of("x", x, "y", 0.3), Map.of("x", x, "y", 0.7))),
                "radius", 0.06);
    }

    @Test
    void strokesBecomeAMaskBoundToTheRevision() {
        var f = fixture();
        assertThat(f.getRaw()).isEqualTo("null");

        var created = f.client().post(f.path(), stroke(1, 0.2));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        Map mask = (Map) created.getBody().get("mask");
        assertThat(created.getBody()).containsEntry("revision", 1).containsEntry("markers", List.of());
        assertThat(mask).containsEntry("kind", "mask").containsEntry("width", 120).containsEntry("height", 80)
                .containsEntry("has_alpha", true);
        assertThat(((Map) f.client().get(f.path()).getBody().get("mask")).get("id")).isEqualTo(mask.get("id"));

        // 再涂一笔与已有选区合并，得到新的遮罩
        var merged = f.client().post(f.path(), stroke(1, 0.8)).getBody();
        assertThat(((Map) merged.get("mask")).get("id")).isNotEqualTo(mask.get("id"));

        // 遮罩是工作素材，不进素材库
        var library = f.client().exchange(HttpMethod.GET, "/api/assets/library", null, String.class).getBody();
        assertThat(library).doesNotContain((String) mask.get("id"));

        // 画布一改，选区失效
        f.client().post("/api/sessions/" + f.sessionId() + "/tools",
                Map.of("tool", "flip_layer", "params", Map.of("direction", "horizontal")));
        assertThat(f.getRaw()).isEqualTo("null");
        var stale = f.client().post(f.path(), stroke(1, 0.5));
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(stale.getBody()).containsEntry("detail", "画布已更新，请重新选择");
        assertThat(f.client().post(f.path(), stroke(999, 0.5)).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void pointsAppendMarkersAndDeleteClears() {
        var f = fixture();
        var first = f.client().post(f.path(), Map.of("revision", 1, "points", List.of(Map.of("x", 0.3, "y", 0.4))))
                .getBody();
        assertThat((List) first.get("markers")).hasSize(1);
        var appended = f.client().post(f.path(), Map.of("revision", 1, "append", true,
                "points", List.of(Map.of("x", 0.7, "y", 0.6)))).getBody();
        assertThat(((List<Map>) appended.get("markers")).stream().map(m -> m.get("index")).toList())
                .containsExactly(1, 2);
        var replaced = f.client().post(f.path(), Map.of("revision", 1, "points", List.of(Map.of("x", 0.5, "y", 0.5))))
                .getBody();
        assertThat((List) replaced.get("markers")).hasSize(1);

        assertThat(f.client().exchange(HttpMethod.DELETE, f.path(), null).getStatusCode().value()).isEqualTo(204);
        assertThat(f.getRaw()).isEqualTo("null");
    }

    @Test
    void rejectsEmptyOrInvalidSelectionsAndOtherUsers() {
        var f = fixture();
        var empty = f.client().post(f.path(), Map.of("revision", 1));
        assertThat(empty.getStatusCode().value()).isEqualTo(422);
        assertThat(empty.getBody()).containsEntry("detail", "请点选或涂抹选区");
        assertThat(f.client().post(f.path(), Map.of("revision", 1, "strokes", List.of(List.of(Map.of("x", 2, "y", 0)))))
                .getStatusCode().value()).isEqualTo(422);
        assertThat(f.client().post(f.path(), Map.of("revision", 1, "radius", 0.5,
                "strokes", List.of(List.of(Map.of("x", 0.5, "y", 0.5))))).getStatusCode().value()).isEqualTo(422);

        var other = ApiClient.registered(http);
        var response = other.post(f.path(), stroke(1, 0.5));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("detail", "会话不存在");
        assertThat(other.get(f.path()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void plannerSeesTheSelectionInTheCanvasSummary() {
        model.reset();
        var f = fixture();
        f.client().post(f.path(), stroke(1, 0.5));
        model.thenText("好的。");
        f.client().post("/api/sessions/" + f.sessionId() + "/messages", Map.of("text", "把选中的东西去掉"));
        assertThat(model.received().getLast().getSystemMessage().getText()).contains("已有笔刷选区");

        model.thenText("好的。");
        f.client().exchange(HttpMethod.DELETE, f.path(), null);
        f.client().post("/api/sessions/" + f.sessionId() + "/messages", Map.of("text", "把选中的东西去掉"));
        assertThat(model.received().getLast().getSystemMessage().getText()).contains("当前没有选区");
    }
}
