package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import io.github.zzzzain777.retouch.config.ImageModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 基础集成测试：真实中间件下应用能启动、迁移能执行、健康检查四项都为 ok。不调用任何模型。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.image.dashscope.api-key="})
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class RetouchAgentApplicationTests {

    @Value("${spring.ai.openai.base-url}")
    String plannerBaseUrl;

    @Value("${spring.ai.openai.chat.model}")
    String plannerModel;

    @Autowired
    ImageModelProperties imageModel;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestRestTemplate http;

    @Test
    void readsModelConfigurationEntries() {
        assertThat(plannerBaseUrl).isNotBlank();
        assertThat(plannerModel).isNotBlank();
        assertThat(imageModel.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com");
        assertThat(imageModel.toString()).doesNotContain("placeholder");
    }

    @Test
    void appliesSchemaMigration() {
        var tables = jdbc.sql("select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class).list();
        assertThat(tables).contains("users", "assets", "edit_sessions", "session_assets", "edit_history",
                "agent_turns", "tool_runs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthReportsEveryDependency() {
        Map<String, String> body = http.getForObject("/api/health", Map.class);
        assertThat(body).containsExactlyInAnyOrderEntriesOf(
                Map.of("api", "ok", "database", "ok", "storage", "ok", "redis", "ok"));
    }
}
