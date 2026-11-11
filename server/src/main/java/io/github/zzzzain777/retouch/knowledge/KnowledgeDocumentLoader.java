package io.github.zzzzain777.retouch.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规范文档加载器：读 classpath 下的 Markdown 规范，按 ## 条款切成片段。
 *
 * <p>每份规范开头的 front-matter 提供 docId / title / platform / category / version，
 * 切出的每个片段都带上这些元数据，供后续按平台、类目过滤和引用。
 */
@Component
public class KnowledgeDocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentLoader.class);

    private static final Pattern FRONT_MATTER =
            Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", Pattern.DOTALL);

    private final ResourcePatternResolver resolver;
    private final String location;

    public KnowledgeDocumentLoader(ResourcePatternResolver resolver,
                                   @Value("${retouch.knowledge.location:classpath:knowledge/*.md}") String location) {
        this.resolver = resolver;
        this.location = location;
    }

    public List<Document> load() {
        List<Document> chunks = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                chunks.addAll(parse(resource));
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载规范文件失败: " + location, e);
        }
        log.info("规范加载完成：{} 个片段", chunks.size());
        return chunks;
    }

    private List<Document> parse(Resource resource) throws IOException {
        String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> meta = new LinkedHashMap<>();
        String body = raw;

        Matcher fm = FRONT_MATTER.matcher(raw);
        if (fm.find()) {
            for (String line : fm.group(1).split("\\R")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    meta.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
            body = raw.substring(fm.end());
        }

        String docId = meta.getOrDefault("docId", resource.getFilename());
        String platform = meta.getOrDefault("platform", "unknown");
        String category = meta.getOrDefault("category", "unknown");
        String title = meta.getOrDefault("title", docId);
        int version = Integer.parseInt(meta.getOrDefault("version", "1"));

        List<Document> out = new ArrayList<>();
        String[] parts = body.split("(?m)^##\\s+");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int nl = part.indexOf('\n');
            String heading = (nl >= 0 ? part.substring(0, nl) : part).trim();
            String text = (nl >= 0 ? part.substring(nl + 1) : "").trim();
            if (text.isEmpty()) {
                continue;
            }

            String ruleId = heading.split("\\s+")[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", docId);
            m.put("docVersion", version);
            m.put("platform", platform);
            m.put("category", category);
            m.put("title", title);
            m.put("ruleId", ruleId);
            m.put("sourceRef", docId + "#" + ruleId);

            out.add(new Document(heading + "\n" + text, m));
        }
        return out;
    }
}
