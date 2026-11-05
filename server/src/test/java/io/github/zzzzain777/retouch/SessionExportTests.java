package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import tools.jackson.databind.json.JsonMapper;

/** 会话导出：默认导出当前画布拼平图的 zip（真实 Python 渲染），带 manifest.json；不在会话里的素材拒绝导出。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test"})
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, ImageServiceTestConfiguration.class})
@EnabledIf("io.github.zzzzain777.retouch.ImageServiceProcess#available")
@SuppressWarnings({"unchecked", "rawtypes"})
class SessionExportTests {

    @Autowired
    TestRestTemplate http;

    private record Fixture(ApiClient client, String sessionId, String assetId) {
    }

    private Fixture fixture() {
        var client = ApiClient.registered(http);
        String asset = (String) client.upload(ApiClient.png(120, 80, false), "原 图.png").getBody().get("id");
        String session = (String) client
                .post("/api/sessions", Map.of("current_asset_id", asset, "title", "双十一 主图！")).getBody().get("id");
        return new Fixture(client, session, asset);
    }

    private static Map<String, byte[]> unzip(byte[] data) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    void defaultExportIsTheFlattenedCanvasWithManifest() throws Exception {
        var f = fixture();
        var response = f.client().exchange(HttpMethod.POST, "/api/sessions/" + f.sessionId() + "/exports",
                Map.of("asset_ids", List.of()), byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        // 标题过滤掉标点、空格换连字符："双十一 主图！" → "双十一-主图"
        assertThat(disposition).startsWith("attachment; filename*=UTF-8''")
                .contains("%E5%8F%8C%E5%8D%81%E4%B8%80-%E4%B8%BB%E5%9B%BE-").endsWith(".zip");

        var entries = unzip(response.getBody());
        // 120×80 不是任何标准比例，槽位用像素尺寸
        assertThat(entries.keySet()).containsExactly("images/01-canvas-120x80.png", "manifest.json");
        assertThat(entries.get("images/01-canvas-120x80.png")).startsWith((byte) 0x89, (byte) 'P', (byte) 'N');

        Map manifest = JsonMapper.builder().build().readValue(entries.get("manifest.json"), Map.class);
        assertThat(manifest).containsEntry("session_id", f.sessionId());
        var items = (List<Map>) manifest.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("file", "images/01-canvas-120x80.png")
                .containsEntry("kind", "canvas").containsEntry("width", 120).containsEntry("height", 80);
        assertThat(items.getFirst().get("asset_id")).isNull();
        assertThat(items.getFirst().get("ratio")).isNull();
    }

    @Test
    void exportingTheCurrentAssetRendersTheCanvasForIt() throws Exception {
        var f = fixture();
        var response = f.client().exchange(HttpMethod.POST, "/api/sessions/" + f.sessionId() + "/exports",
                Map.of("asset_ids", List.of(f.assetId())), byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var entries = unzip(response.getBody());
        assertThat(entries.keySet()).containsExactly("images/01-canvas-120x80.png", "manifest.json");
    }

    @Test
    void assetsOutsideTheSessionAreRejected() {
        var f = fixture();
        var response = f.client().post("/api/sessions/" + f.sessionId() + "/exports",
                Map.of("asset_ids", List.of(UUID.randomUUID().toString())));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("detail", "素材不在该会话中");
    }

    @Test
    void anotherUsersAssetIdIsAlsoRejected() {
        var mine = fixture();
        var other = fixture();
        var response = mine.client().post("/api/sessions/" + mine.sessionId() + "/exports",
                Map.of("asset_ids", List.of(other.assetId())));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("detail", "素材不在该会话中");
    }
}
