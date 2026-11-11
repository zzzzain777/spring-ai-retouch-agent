package io.github.zzzzain777.retouch.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 规范入库：按 docId 计算内容哈希，未变化跳过；变化则先删旧向量再写新向量。
 *
 * <p>幂等依靠 knowledge_doc 表的 content_hash；向量删除按元数据 docId 过滤，
 * 避免重复启动把同一份规范写入多遍。
 */
@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);

    private final KnowledgeDocumentLoader loader;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;

    public KnowledgeIndexService(KnowledgeDocumentLoader loader, VectorStore vectorStore, JdbcTemplate jdbc) {
        this.loader = loader;
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
    }

    private volatile boolean indexed;

    /** 首次调用时入库；幂等，重复调用不再执行。 */
    public synchronized void ensureIndexed() {
        if (indexed) {
            return;
        }
        try {
            indexAll();
            indexed = true;
        } catch (Exception e) {
            log.error("规范入库失败", e);
        }
    }

    public void indexAll() {
        Map<String, List<Document>> byDoc = loader.load().stream()
                .collect(Collectors.groupingBy(
                        d -> (String) d.getMetadata().get("docId"),
                        LinkedHashMap::new,
                        Collectors.toList()));

        for (Map.Entry<String, List<Document>> entry : byDoc.entrySet()) {
            String docId = entry.getKey();
            List<Document> chunks = entry.getValue();
            String hash = sha256(chunks.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n")));

            String oldHash = jdbc.query(
                    "select content_hash from knowledge_doc where doc_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    docId);

            if (hash.equals(oldHash)) {
                log.info("规范 {} 未变化，跳过入库（{} 块）", docId, chunks.size());
                continue;
            }

            vectorStore.delete(new FilterExpressionBuilder().eq("docId", docId).build());
            vectorStore.add(chunks);

            Document first = chunks.get(0);
            jdbc.update("""
                    insert into knowledge_doc(doc_id, version, title, platform, category, content_hash)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (doc_id) do update set
                        version = excluded.version,
                        title = excluded.title,
                        platform = excluded.platform,
                        category = excluded.category,
                        content_hash = excluded.content_hash,
                        updated_at = now()
                    """,
                    docId,
                    first.getMetadata().get("docVersion"),
                    first.getMetadata().get("title"),
                    first.getMetadata().get("platform"),
                    first.getMetadata().get("category"),
                    hash);

            log.info("规范 {} 入库完成：{} 块", docId, chunks.size());
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算哈希失败", e);
        }
    }
}
