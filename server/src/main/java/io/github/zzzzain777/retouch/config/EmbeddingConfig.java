package io.github.zzzzain777.retouch.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 规范检索用的嵌入模型：走 OpenAI 兼容接口，服务商与模型名都在配置里，代码不写死。
 *
 * <p>密钥与接口地址放环境变量或未入库的 application-local.yml；向量维度必须与
 * spring.ai.vectorstore.pgvector.dimensions 一致，否则写入向量库会失败。
 */
@Configuration
public class EmbeddingConfig {

    @Primary
    @Bean
    public EmbeddingModel knowledgeEmbeddingModel(
            @Value("${retouch.knowledge.embedding.api-key:}") String apiKey,
            @Value("${retouch.knowledge.embedding.base-url:}") String baseUrl,
            @Value("${retouch.knowledge.embedding.model:}") String model
    ) {
        return new OpenAiEmbeddingModel(
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .model(model)
                        .build());
    }
}
