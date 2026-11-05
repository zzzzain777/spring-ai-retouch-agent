package io.github.zzzzain777.retouch;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 测试里模拟一个浏览器用户：自己保存会话 Cookie，所有请求都经过真实的 HTTP 与安全过滤链。
 * 每个用户一个独立的 HTTP 客户端：注入的 TestRestTemplate 底层是带共享 Cookie 存储的 Apache HttpClient，
 * 多个模拟用户共用它会互相串号。
 */
public class ApiClient {

    private final TestRestTemplate http;
    private String cookie;

    public ApiClient(TestRestTemplate shared) {
        var builder = new RestTemplateBuilder().rootUri(shared.getRootUri())
                .requestFactory(() -> new JdkClientHttpRequestFactory(java.net.http.HttpClient.newHttpClient()));
        this.http = new TestRestTemplate(builder);
    }

    public static ApiClient registered(TestRestTemplate http) {
        ApiClient client = new ApiClient(http);
        var response = client.post("/api/auth/register",
                Map.of("username", "u_" + UUID.randomUUID().toString().substring(0, 8), "password", "secret-123"));
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("注册失败：" + response);
        }
        return client;
    }

    /** 读一条 SSE 流直到服务端断开，返回所有 data 帧（按上游格式 "data: {json}"）。 */
    public java.util.List<Map<String, Object>> sse(String path) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(http.getRootUri() + path))
                .timeout(java.time.Duration.ofSeconds(90));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        var response = java.net.http.HttpClient.newHttpClient().send(request.build(),
                java.net.http.HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("SSE 状态码 " + response.statusCode());
        }
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var frames = new java.util.ArrayList<Map<String, Object>>();
        response.body().filter(line -> line.startsWith("data: "))
                .forEach(line -> frames.add(mapper.readValue(line.substring(6), Map.class)));
        return frames;
    }

    /** 不带本客户端 Cookie 的原始请求，用来构造伪造或缺失的会话。 */
    public TestRestTemplate raw() {
        return http;
    }

    public ResponseEntity<Map> post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    /** 带额外请求头的 POST（例如 Idempotency-Key）。 */
    public ResponseEntity<Map> post(String path, Object body, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::add);
        return remember(http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(headers)), Map.class));
    }

    public ResponseEntity<Map> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    public ResponseEntity<Map> exchange(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return remember(http.exchange(path, method, new HttpEntity<>(body, headers(headers)), Map.class));
    }

    public <T> ResponseEntity<T> exchange(HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return remember(http.exchange(path, method, new HttpEntity<>(body, headers(headers)), type));
    }

    public ResponseEntity<Map> upload(byte[] data, String filename) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return remember(http.exchange("/api/assets", HttpMethod.POST, new HttpEntity<>(form, headers(headers)),
                Map.class));
    }

    private HttpHeaders headers(HttpHeaders headers) {
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    private <T> ResponseEntity<T> remember(ResponseEntity<T> response) {
        var setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookie != null) {
            for (String value : setCookie) {
                if (value.startsWith("session=")) {
                    String token = value.substring(0, value.indexOf(';'));
                    cookie = token.equals("session=") ? null : token;
                }
            }
        }
        return response;
    }

    public static byte[] png(int width, int height, boolean alpha) {
        var image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(40, 90, 170, alpha ? 128 : 255));
        graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return out.toByteArray();
    }
}
