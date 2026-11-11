package io.github.zzzzain777.retouch.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规范检索：先按 platform + category 做元数据过滤，再做向量 topK。
 *
 * <p>两个入口的阈值不同：规范问答要求问得准才答（阈值高），合规检查宁可多召回几条再逐条判规则（阈值低、topK 大）。
 * 两者都可能返回空列表，表示“没有检索到依据”，调用方据此交人工，而不是让模型猜。
 */
@Service
public class KnowledgeRetriever {

    private final VectorStore vectorStore;
    private final int topK;
    private final double threshold;
    private final int complianceTopK;
    private final double complianceThreshold;

    public KnowledgeRetriever(VectorStore vectorStore,
                              @Value("${retouch.knowledge.top-k:3}") int topK,
                              @Value("${retouch.knowledge.similarity-threshold:0.5}") double threshold,
                              @Value("${retouch.knowledge.compliance.top-k:5}") int complianceTopK,
                              @Value("${retouch.knowledge.compliance.similarity-threshold:0.3}") double complianceThreshold) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.threshold = threshold;
        this.complianceTopK = complianceTopK;
        this.complianceThreshold = complianceThreshold;
    }

    /** 规范问答：按问题召回最相关的几条条款，不相关就返回空。 */
    public List<Document> search(String platform, String category, String query) {
        return search(platform, category, query, topK, threshold);
    }

    /** 合规检查：按本轮目标召回适用条款，阈值更低、条数更多，避免该查的规则没被召回。 */
    public List<Document> searchForCompliance(String platform, String category, String query) {
        return search(platform, category, query, complianceTopK, complianceThreshold);
    }

    private List<Document> search(String platform, String category, String query, int k, double minScore) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(k)
                .similarityThreshold(minScore)
                .filterExpression(scope(platform, category))
                .build();

        return vectorStore.similaritySearch(request);
    }

    private static Filter.Expression scope(String platform, String category) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return b.and(
                b.eq("platform", platform),
                b.eq("category", category)).build();
    }
}
