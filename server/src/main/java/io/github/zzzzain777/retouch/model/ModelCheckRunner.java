package io.github.zzzzain777.retouch.model;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 连通性检查：用配置里的真实密钥调用一次规划模型，打印回复、模型名、token 用量和耗时，然后退出。
 *
 * <p>只做一次纯文本对话，不注册任何工具，不会产生副作用。启动方式：
 * {@code ./mvnw spring-boot:run -Dspring-boot.run.arguments=--retouch.model-check.enabled=true}
 */
@Component
@ConditionalOnProperty(name = "retouch.model-check.enabled", havingValue = "true")
class ModelCheckRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelCheckRunner.class);

    private final ChatClient chatClient;
    private final ConfigurableApplicationContext context;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    ModelCheckRunner(
            ChatClient.Builder builder,
            ConfigurableApplicationContext context,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:}") String baseUrl,
            @Value("${spring.ai.openai.chat.model:}") String model) {
        this.chatClient = builder.build();
        this.context = context;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = check();
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    int check() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("未配置规划模型密钥：请在 src/main/resources/application-local.yml 填写 spring.ai.openai.api-key，"
                    + "或设置环境变量 DEEPSEEK_API_KEY");
            return 2;
        }
        log.info("模型连通性检查：base-url={} model={}", baseUrl, model);
        Instant started = Instant.now();
        try {
            ChatResponse response = chatClient.prompt()
                    .user("这是一次接口连通性检查，请只回复两个字母：OK")
                    .call()
                    .chatResponse();
            long elapsedMs = Duration.between(started, Instant.now()).toMillis();
            String text = response.getResult().getOutput().getText();
            Usage usage = response.getMetadata().getUsage();
            log.info("模型连通性检查通过：reply={} model={} promptTokens={} completionTokens={} elapsedMs={}",
                    text == null ? "" : text.strip(), response.getMetadata().getModel(),
                    usage.getPromptTokens(), usage.getCompletionTokens(), elapsedMs);
            return 0;
        } catch (RuntimeException exc) {
            log.error("模型连通性检查失败：{}: {}", exc.getClass().getSimpleName(), exc.getMessage());
            return 1;
        }
    }
}
