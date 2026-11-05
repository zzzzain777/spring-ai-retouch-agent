package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.openai.api-key=placeholder-for-context-test")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class AuthAndAssetTests {

    @Autowired
    TestRestTemplate http;

    @Test
    void registerLoginMeLogout() {
        String name = "user_" + UUID.randomUUID().toString().substring(0, 8);
        var alice = new ApiClient(http);
        var created = alice.post("/api/auth/register", Map.of("username", name, "password", "secret-123"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).containsEntry("username", name).containsKey("id");
        assertThat(created.getHeaders().getFirst("Set-Cookie")).contains("session=").contains("HttpOnly")
                .contains("SameSite=Lax");

        assertThat(alice.get("/api/auth/me").getBody()).containsEntry("username", name);

        var duplicate = new ApiClient(http).post("/api/auth/register", Map.of("username", name, "password", "other-123"));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody()).containsEntry("detail", "该用户名已被占用");

        var wrong = new ApiClient(http).post("/api/auth/login", Map.of("username", name, "password", "wrong-pass"));
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);

        var bob = new ApiClient(http);
        assertThat(bob.post("/api/auth/login", Map.of("username", name, "password", "secret-123"))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(bob.get("/api/auth/me").getStatusCode().value()).isEqualTo(200);

        assertThat(alice.post("/api/auth/logout", null).getStatusCode().value()).isEqualTo(204);
        assertThat(alice.get("/api/auth/me").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void rejectsInvalidCredentialsShape() {
        var client = new ApiClient(http);
        assertThat(client.post("/api/auth/register", Map.of("username", "ab", "password", "secret-123"))
                .getStatusCode().value()).isEqualTo(422);
        assertThat(client.post("/api/auth/register", Map.of("username", "bad name!", "password", "secret-123"))
                .getStatusCode().value()).isEqualTo(422);
        assertThat(client.post("/api/auth/register", Map.of("username", "short_pw", "password", "123"))
                .getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void anonymousRequestsGet401WithDetail() {
        var anon = new ApiClient(http);
        var response = anon.get("/api/assets");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsEntry("detail", "未登录或会话已过期");
        var forged = new ApiClient(http).raw().exchange("/api/assets", HttpMethod.GET, new org.springframework.http.HttpEntity<>(
                headersWithCookie("session=not-a-jwt")), Map.class);
        assertThat(forged.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void uploadStoresImageAndOnlyOwnerCanRead() throws Exception {
        var alice = ApiClient.registered(http);
        var uploaded = alice.upload(ApiClient.png(640, 480, true), "product.png");
        assertThat(uploaded.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> asset = (Map<String, Object>) uploaded.getBody();
        assertThat(asset).containsEntry("kind", "original").containsEntry("source", "upload")
                .containsEntry("image_format", "PNG").containsEntry("width", 640).containsEntry("height", 480)
                .containsEntry("has_alpha", true).containsKeys("id", "size_bytes", "created_at", "url");

        // 签名 URL 能直接下载到同样的字节
        var download = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create((String) asset.get("url")))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).hasSize(((Number) asset.get("size_bytes")).intValue());

        String id = (String) asset.get("id");
        assertThat(alice.get("/api/assets/" + id).getStatusCode().value()).isEqualTo(200);
        var listed = alice.exchange(HttpMethod.GET, "/api/assets", null, List.class);
        assertThat(listed.getBody()).hasSize(1);

        var bob = ApiClient.registered(http);
        var stolen = bob.get("/api/assets/" + id);
        assertThat(stolen.getStatusCode().value()).isEqualTo(404);
        assertThat(stolen.getBody()).containsEntry("detail", "素材不存在");
        assertThat(bob.exchange(HttpMethod.GET, "/api/assets", null, List.class).getBody()).isEmpty();
    }

    @Test
    void rejectsUnsupportedOrTinyImages() {
        var alice = ApiClient.registered(http);
        var text = alice.upload("hello".getBytes(), "note.txt");
        assertThat(text.getStatusCode().value()).isEqualTo(422);
        assertThat(text.getBody()).containsEntry("detail", "文件已损坏或不是受支持的图片");
        var tiny = alice.upload(ApiClient.png(16, 16, false), "tiny.png");
        assertThat(tiny.getStatusCode().value()).isEqualTo(422);
        assertThat(alice.get("/api/assets/not-a-uuid").getStatusCode().value()).isEqualTo(422);
        var tooLarge = alice.upload(new byte[20 * 1024 * 1024 + 1], "huge.png");
        assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
        assertThat(alice.exchange(HttpMethod.GET, "/api/assets", null, List.class).getBody()).isEmpty();
    }

    private static org.springframework.http.HttpHeaders headersWithCookie(String cookie) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.add("Cookie", cookie);
        return headers;
    }
}
