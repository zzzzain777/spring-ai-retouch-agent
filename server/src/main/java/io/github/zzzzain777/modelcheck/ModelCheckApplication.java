package io.github.zzzzain777.modelcheck;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 模型连通性检查：用配置里的真实密钥调用一次规划模型，打印回复、模型名、token 用量和耗时，然后退出。
 *
 * <p>独立于主应用的包，不扫描业务组件，也不连数据库、Redis 和对象存储，所以在没有中间件的机器上也能运行。
 * 只做一次纯文本对话，不注册任何工具，不会产生副作用。运行方式见 {@code server/scripts/model-check.sh}。
 *
 * <p>退出码：0 通过，1 调用失败，2 没配密钥。
 */
@SpringBootApplication(excludeName = {
        // 只需要规划模型：不连数据库、不跑迁移、不连 Redis
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"})
public class ModelCheckApplication {

    private static final Logger log = LoggerFactory.getLogger(ModelCheckApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(ModelCheckApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
        int exitCode = check(context.getBean(ChatClient.Builder.class).build(), context.getEnvironment());
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    static int check(ChatClient chatClient, Environment env) {
        String apiKey = env.getProperty("spring.ai.openai.api-key", "");
        if (apiKey.isBlank()) {
            log.error("未配置规划模型密钥：请在 src/main/resources/application-local.yml 填写 spring.ai.openai.api-key，"
                    + "或设置环境变量 DEEPSEEK_API_KEY");
            return 2;
        }
        log.info("模型连通性检查：base-url={} model={}",
                env.getProperty("spring.ai.openai.base-url"), env.getProperty("spring.ai.openai.chat.model"));
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
