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
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.openai.api-key=placeholder-for-context-test")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class SessionTests {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient jdbc;

    private static String upload(ApiClient client, int width, int height) {
        var response = client.upload(ApiClient.png(width, height, false), "img.png");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return (String) response.getBody().get("id");
    }

    private static Map<String, Object> create(ApiClient client, String current, List<String> wall, String title) {
        var body = new java.util.HashMap<String, Object>();
        body.put("current_asset_id", current);
        body.put("asset_ids", wall);
        if (title != null) {
            body.put("title", title);
        }
        var response = client.post("/api/sessions", body);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody();
    }

    @Test
    void createBuildsLockedBaseLayerAndFirstHistoryEntry() {
        var alice = ApiClient.registered(http);
        String a = upload(alice, 400, 300);
        String b = upload(alice, 200, 200);
        var session = create(alice, a, List.of(b, a), "  新品   主图  ");

        assertThat(session).containsEntry("title", "新品 主图").containsEntry("revision", 1)
                .containsEntry("history_seq", 1).containsEntry("original_asset_id", a)
                .containsEntry("current_asset_id", a).containsEntry("can_undo", false)
                .containsEntry("can_redo", false).containsEntry("previous_document", null);
        var document = (Map) session.get("document");
        assertThat(document).containsEntry("width", 400).containsEntry("height", 300);
        var layers = (List<Map>) document.get("layers");
        assertThat(layers).hasSize(1);
        assertThat(layers.getFirst()).containsEntry("id", "base").containsEntry("kind", "image")
                .containsEntry("name", "底图").containsEntry("asset_id", a).containsEntry("locked", true)
                .containsEntry("visible", true).containsKey("transform").containsKey("font_size");
        var wall = ((List<Map>) session.get("assets")).stream().map(m -> m.get("id")).toList();
        assertThat(wall).containsExactly(a, b);

        var history = alice.exchange(HttpMethod.GET, "/api/sessions/" + session.get("id") + "/history", null,
                List.class).getBody();
        assertThat(history).hasSize(1);
        assertThat((Map) history.getFirst()).containsEntry("seq", 1).containsEntry("action", "create_session");

        // 文档与历史快照以 jsonb 对象落库，而不是被转义成字符串
        assertThat(jdbc.sql("select jsonb_typeof(document) from edit_sessions where id = ?::uuid")
                .param(session.get("id")).query(String.class).single()).isEqualTo("object");
        assertThat(jdbc.sql("select jsonb_typeof(result -> 'document') from edit_history where session_id = ?::uuid")
                .param(session.get("id")).query(String.class).single()).isEqualTo("object");

        var listed = alice.exchange(HttpMethod.GET, "/api/sessions", null, List.class).getBody();
        assertThat((Map) listed.getFirst()).containsEntry("id", session.get("id")).doesNotContainKey("document");
    }

    @Test
    void switchUndoRedoFollowLinearHistory() {
        var alice = ApiClient.registered(http);
        String a = upload(alice, 400, 300);
        String b = upload(alice, 320, 320);
        String id = (String) create(alice, a, List.of(), null).get("id");
        String base = "/api/sessions/" + id;

        var switched = alice.exchange(HttpMethod.PATCH, base, Map.of("current_asset_id", b), Map.class).getBody();
        assertThat(switched).containsEntry("title", "未命名会话").containsEntry("current_asset_id", b)
                .containsEntry("revision", 2).containsEntry("history_seq", 2).containsEntry("can_undo", true);
        assertThat((Map) switched.get("document")).containsEntry("width", 320);
        assertThat((Map) switched.get("previous_document")).containsEntry("width", 400);
        assertThat(((List<Map>) switched.get("assets")).stream().map(m -> m.get("id")).toList()).containsExactly(a, b);

        // 再点当前图不产生历史
        var same = alice.exchange(HttpMethod.PATCH, base, Map.of("current_asset_id", b), Map.class).getBody();
        assertThat(same).containsEntry("history_seq", 2).containsEntry("revision", 2);

        var undone = alice.post(base + "/undo", null).getBody();
        assertThat(undone).containsEntry("current_asset_id", a).containsEntry("revision", 1)
                .containsEntry("history_seq", 1).containsEntry("can_undo", false).containsEntry("can_redo", true);
        var noMore = alice.post(base + "/undo", null);
        assertThat(noMore.getStatusCode().value()).isEqualTo(409);
        assertThat(noMore.getBody()).containsEntry("detail", "没有可撤销的操作");

        var redone = alice.post(base + "/redo", null).getBody();
        assertThat(redone).containsEntry("current_asset_id", b).containsEntry("revision", 2)
                .containsEntry("history_seq", 2).containsEntry("can_redo", false);
        assertThat(alice.post(base + "/redo", null).getBody()).containsEntry("detail", "没有可重做的操作");

        // 撤销后做新编辑会截断重做分支；历史按 seq 倒序
        alice.post(base + "/undo", null);
        String c = upload(alice, 100, 100);
        alice.exchange(HttpMethod.PATCH, base, Map.of("current_asset_id", c), Map.class);
        var history = alice.exchange(HttpMethod.GET, base + "/history", null, List.class).getBody();
        assertThat(history.stream().map(e -> ((Map) e).get("seq")).toList()).containsExactly(2, 1);
        var latest = (Map) history.getFirst();
        assertThat(latest).containsEntry("action", "switch_current");
        assertThat((Map) latest.get("params")).containsKey("before");
        assertThat((Map) latest.get("result")).containsEntry("current_asset_id", c).containsEntry("revision", 2);
        assertThat(alice.post(base + "/redo", null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void historyKeepsOnlyTwentyEntries() {
        var alice = ApiClient.registered(http);
        String a = upload(alice, 64, 64);
        String b = upload(alice, 96, 96);
        String base = "/api/sessions/" + create(alice, a, List.of(b), null).get("id");
        for (int i = 0; i < 25; i++) {
            alice.exchange(HttpMethod.PATCH, base, Map.of("current_asset_id", i % 2 == 0 ? b : a), Map.class);
        }
        var history = alice.exchange(HttpMethod.GET, base + "/history", null, List.class).getBody();
        assertThat(history).hasSize(20);
        assertThat(((Map) history.getFirst()).get("seq")).isEqualTo(26);
        assertThat(((Map) history.getLast()).get("seq")).isEqualTo(7);
        int undos = 0;
        while (alice.post(base + "/undo", null).getStatusCode().value() == 200) {
            undos++;
        }
        assertThat(undos).isEqualTo(20);
    }

    @Test
    void concurrentEditsAreSerializedPerSession() throws Exception {
        var alice = ApiClient.registered(http);
        String a = upload(alice, 64, 64);
        String base = "/api/sessions/" + create(alice, a, List.of(), null).get("id");
        var targets = new java.util.ArrayList<String>();
        for (int i = 0; i < 8; i++) {
            targets.add(upload(alice, 40 + i, 40 + i));
        }
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = targets.stream().map(t -> pool.submit(() -> alice.exchange(HttpMethod.PATCH, base,
                    Map.of("current_asset_id", t), Map.class).getStatusCode().value())).toList();
            for (var future : futures) {
                assertThat(future.get()).isEqualTo(200);
            }
        }
        var detail = alice.get(base).getBody();
        assertThat(detail).containsEntry("history_seq", 9).containsEntry("revision", 9);
        var seqs = alice.exchange(HttpMethod.GET, base + "/history", null, List.class).getBody().stream()
                .map(e -> ((Map) e).get("seq")).toList();
        assertThat(seqs).containsExactly(9, 8, 7, 6, 5, 4, 3, 2, 1);
    }

    @Test
    void sessionsAreIsolatedPerUser() {
        var alice = ApiClient.registered(http);
        String a = upload(alice, 64, 64);
        String id = (String) create(alice, a, List.of(), null).get("id");
        var bob = ApiClient.registered(http);

        var read = bob.get("/api/sessions/" + id);
        assertThat(read.getStatusCode().value()).isEqualTo(404);
        assertThat(read.getBody()).containsEntry("detail", "会话不存在");
        assertThat(bob.post("/api/sessions/" + id + "/undo", null).getStatusCode().value()).isEqualTo(404);
        assertThat(bob.exchange(HttpMethod.PATCH, "/api/sessions/" + id, Map.of("title", "x"), Map.class)
                .getStatusCode().value()).isEqualTo(404);
        var stolen = bob.post("/api/sessions", Map.of("current_asset_id", a));
        assertThat(stolen.getStatusCode().value()).isEqualTo(404);
        assertThat(stolen.getBody()).containsEntry("detail", "素材不存在");
        // alice 不能把 bob 的会话切到自己的图，也不能把自己的会话切到 bob 的图
        String bobAsset = upload(bob, 64, 64);
        assertThat(alice.exchange(HttpMethod.PATCH, "/api/sessions/" + id, Map.of("current_asset_id", bobAsset),
                Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(alice.post("/api/sessions", Map.of()).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void libraryGroupsWallsAndOrphans() {
        var alice = ApiClient.registered(http);
        String a = upload(alice, 64, 64);
        String b = upload(alice, 80, 80);
        String loose = upload(alice, 90, 90);
        var session = create(alice, a, List.of(b), "主图");

        var groups = alice.exchange(HttpMethod.GET, "/api/assets/library", null, List.class).getBody();
        assertThat(groups).hasSize(2);
        var first = (Map) groups.get(0);
        assertThat(first).containsEntry("session_id", session.get("id")).containsEntry("title", "主图");
        assertThat((Map) first.get("cover")).containsEntry("id", a);
        assertThat(((List<Map>) first.get("assets")).stream().map(m -> m.get("id")).toList()).containsExactly(a, b);
        var orphan = (Map) groups.get(1);
        assertThat(orphan).containsEntry("session_id", null).containsEntry("title", "未归入会话");
        assertThat(((List<Map>) orphan.get("assets")).stream().map(m -> m.get("id")).toList()).containsExactly(loose);

        assertThat(ApiClient.registered(http).exchange(HttpMethod.GET, "/api/assets/library", null, List.class)
                .getBody()).isEmpty();
    }
}
