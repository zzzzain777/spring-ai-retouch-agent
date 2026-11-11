package io.github.zzzzain777.retouch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 嵌入模型的连通性冒烟：会真的调用远程嵌入接口，需要本机配置密钥，因此默认不运行。
 * 显式打开：{@code ./mvnw test -Dtest=EmbeddingConfigTest -Dretouch.real-embedding=true}。
 *
 * <p>只断言向量维度与 pgvector 表的 1024 维一致；检索与合规的判定逻辑由
 * {@code ComplianceCheckerTest} 覆盖，不依赖远程接口。
 */
@SpringBootTest(classes = EmbeddingConfig.class)
@EnabledIfSystemProperty(named = "retouch.real-embedding", matches = "true")
class EmbeddingConfigTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void embeddingDimensionMatchesVectorStore() {
        assertEquals(1024, embeddingModel.embed("首图背景必须为纯白").length);
    }
}
