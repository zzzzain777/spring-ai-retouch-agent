package io.github.zzzzain777.retouch;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * 集成测试里启动真实的 Python 图像服务（../image-service，用 uv 运行），不是替身。
 * 本机没有 uv 时相关测试会被跳过并在报告里显示为 skipped。
 */
public final class ImageServiceProcess implements AutoCloseable {

    public static final String TOKEN = "test-internal-token";

    private final Process process;
    private final String url;

    private ImageServiceProcess(Process process, String url) {
        this.process = process;
        this.url = url;
    }

    public static boolean available() {
        if (!Files.isDirectory(serviceDir())) {
            return false;
        }
        try {
            return new ProcessBuilder("uv", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException missing) {
            return false;
        }
    }

    static Path serviceDir() {
        return Path.of("..", "image-service").toAbsolutePath().normalize();
    }

    public static ImageServiceProcess start(String s3Endpoint) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var builder = new ProcessBuilder("uv", "run", "--frozen", "python", "-m", "uvicorn", "app.main:app",
                "--host", "127.0.0.1", "--port", Integer.toString(port))
                .directory(serviceDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(Path.of("target", "image-service-test.log").toFile());
        var env = builder.environment();
        env.put("IMAGE_SERVICE_TOKEN", TOKEN);
        env.put("S3_ENDPOINT", s3Endpoint);
        env.put("S3_BUCKET", "retouch");
        env.put("S3_ACCESS_KEY", "retouch");
        env.put("S3_SECRET_KEY", "retouch-secret");
        Process process = builder.start();
        String url = "http://127.0.0.1:" + port;
        var client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException("图像服务启动失败，见 target/image-service-test.log");
            }
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create(url + "/healthz")).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return new ImageServiceProcess(process, url);
                }
            } catch (IOException notYet) {
                // 还没起来
            }
            Thread.sleep(300);
        }
        process.destroyForcibly();
        throw new IllegalStateException("图像服务 90 秒内没有就绪");
    }

    public String url() {
        return url;
    }

    @Override
    public void close() {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
    }
}
