package io.github.zzzzain777.retouch;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 测试用的假 DashScope 图像接口：只模拟请求格式与返回结构（编辑结果是纯红图，文生图结果是纯绿图），
 * 用来验证 Java → Python → 模型服务 → 自有存储这条链路。**不代表任何真实模型的效果**，不能当作模型验证。
 */
public final class FakeDashScopeServer implements AutoCloseable {

    public static final String KEY = "test-model-key";
    public static final Color EDIT = new Color(200, 30, 30);
    public static final Color GENERATED = new Color(30, 160, 60);

    public record Received(String path, JsonNode body, String authorization) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, List<String>> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public FakeDashScopeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void reset() {
        received.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/files/")) {
                byte[] data = files.get(path);
                send(exchange, data == null ? 404 : 200, data == null ? new byte[0] : data, "image/png");
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + KEY).equals(auth)) {
                json(exchange, 401, Map.of("code", "InvalidApiKey", "message", "Invalid API-key provided."));
                return;
            }
            if (path.startsWith("/api/v1/tasks/")) {
                String id = path.substring(path.lastIndexOf('/') + 1);
                json(exchange, 200, Map.of("output", Map.of("task_id", id, "task_status", "SUCCEEDED", "choices",
                        choices(tasks.getOrDefault(id, List.of())))));
                return;
            }
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            received.add(new Received(path, body, auth));
            JsonNode params = body.path("parameters");
            int n = Math.max(1, params.path("n").asInt(1));
            if (path.endsWith("/multimodal-generation/generation")) {
                String image = body.path("input").path("messages").get(0).path("content").get(0).path("image")
                        .asString();
                BufferedImage source = ImageIO.read(new ByteArrayInputStream(
                        Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1))));
                int[] size = size(params, source.getWidth(), source.getHeight());
                var urls = new ArrayList<String>();
                for (int i = 0; i < n; i++) {
                    urls.add(file(size[0], size[1], EDIT));
                }
                json(exchange, 200, Map.of("output", Map.of("choices", choices(urls))));
                return;
            }
            if (path.endsWith("/image-generation/generation")) {
                int[] size = size(params, 64, 64);
                var urls = new ArrayList<String>();
                for (int i = 0; i < n; i++) {
                    urls.add(file(size[0], size[1], GENERATED));
                }
                String task = "task-" + counter.incrementAndGet();
                tasks.put(task, urls);
                json(exchange, 200, Map.of("output", Map.of("task_id", task, "task_status", "PENDING")));
                return;
            }
            json(exchange, 404, Map.of("code", "NotFound", "message", path));
        }
    }

    private static int[] size(JsonNode params, int width, int height) {
        String size = params.path("size").asString("");
        if (size.contains("*")) {
            String[] parts = size.split("\\*");
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        }
        return new int[] {width, height};
    }

    private String file(int width, int height, Color color) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        String path = "/files/" + counter.incrementAndGet() + ".png";
        files.put(path, out.toByteArray());
        return url() + path;
    }

    private static List<Map<String, Object>> choices(List<String> urls) {
        return List.of(Map.of("message", Map.of("role", "assistant", "content",
                urls.stream().map(url -> Map.of("image", url)).toList())));
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void send(HttpExchange exchange, int status, byte[] data, String type) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length > 0) {
            exchange.getResponseBody().write(data);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
