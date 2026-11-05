package io.github.zzzzain777.retouch.tool;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 调 Python 图像服务。只传对象键与参数，请求头带任务 id 与第几次执行；输出前缀由任务 id 与 attempt 决定，
 * 同一任务重复执行覆盖同一位置。图像服务的可读错误转成 {@link ToolError}，超时与连不上也一样。
 */
@Component
public class ImageServiceClient {

    private final ImageServiceProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .proxy(HttpClient.Builder.NO_PROXY).build();

    public ImageServiceClient(ImageServiceProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    public static String outputPrefix(UUID runId, int attempt) {
        return "runs/%s/%d/".formatted(runId, attempt);
    }

    public List<ImageOutput> call(String op, UUID runId, int attempt, Map<String, Object> body, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        var client = RestClient.builder().requestFactory(factory).baseUrl(props.baseUrl()).build();
        try {
            JsonNode response = client.post().uri("/v1/ops/{op}", op)
                    .header("X-Internal-Token", props.token())
                    .header("X-Run-Id", runId.toString())
                    .header("X-Attempt", Integer.toString(attempt))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return json.convertValue(response.path("outputs"),
                    json.getTypeFactory().constructCollectionType(List.class, ImageOutput.class));
        } catch (RestClientResponseException failure) {
            String message = readError(failure.getResponseBodyAsString());
            throw new ToolError(message != null ? message : "图像服务处理失败（HTTP " + failure.getStatusCode().value() + "）");
        } catch (ResourceAccessException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new ToolError("图像服务超时");
            }
            if (cause instanceof ConnectException) {
                throw new ToolError("图像服务不可用");
            }
            throw new ToolError("图像服务通信失败");
        }
    }

    private String readError(String body) {
        try {
            JsonNode node = json.readTree(body);
            return node.hasNonNull("error") ? node.get("error").asString() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }
}
