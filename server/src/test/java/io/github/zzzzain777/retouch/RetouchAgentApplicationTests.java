package io.github.zzzzain777.retouch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zzzzain777.retouch.config.ImageModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 只验证配置入口能被读取、应用能在没有真实密钥时启动；不调用任何模型。
 */
@SpringBootTest(properties = {"spring.ai.openai.api-key=placeholder-for-context-test", "retouch.image.dashscope.api-key="})
class RetouchAgentApplicationTests {

    @Value("${spring.ai.openai.base-url}")
    String plannerBaseUrl;

    @Value("${spring.ai.openai.chat.model}")
    String plannerModel;

    @Autowired
    ImageModelProperties imageModel;

    @Test
    void readsModelConfigurationEntries() {
        assertThat(plannerBaseUrl).isNotBlank();
        assertThat(plannerModel).isNotBlank();
        assertThat(imageModel.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com");
        assertThat(imageModel.toString()).doesNotContain("placeholder");
    }
}
