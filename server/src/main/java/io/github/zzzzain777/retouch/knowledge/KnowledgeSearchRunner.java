package io.github.zzzzain777.retouch.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检索与合规冒烟：仅在 retouch.knowledge.demo=true 时运行，用于手动验证召回、空召回和合规拦截。
 * 正常启动不会执行，也不影响任何业务。
 */
@Component
@ConditionalOnProperty(name = "retouch.knowledge.demo", havingValue = "true")
public class KnowledgeSearchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchRunner.class);

    private static final String GOAL = "把这张商品图做成京东服饰类目的首图";

    private final KnowledgeRetriever retriever;
    private final ComplianceChecker checker;
    private final KnowledgeIndexService indexService;

    public KnowledgeSearchRunner(KnowledgeRetriever retriever, ComplianceChecker checker,
                                 KnowledgeIndexService indexService) {
        this.retriever = retriever;
        this.checker = checker;
        this.indexService = indexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        indexService.ensureIndexed();

        // 规范问答：问得准才答
        hits("问答·相关", retriever.search("jd", "apparel", "首图背景有什么要求"));
        hits("问答·无关", retriever.search("jd", "apparel", "退货政策是什么"));

        // 合规：检索词由目标 + 计划工具拼成，召回的是这次场景适用的条款
        demo("只翻转，没处理背景", GOAL, List.of("flip_layer"));
        demo("去背景并裁成正方形", GOAL, List.of("remove_background", "crop_canvas"));
        demo("去背景后加了文字", GOAL, List.of("remove_background", "set_layer_text"));
        demo("目标与商品图无关", "帮我写一首关于春天的诗", List.of("flip_layer"));
    }

    private void hits(String title, List<Document> docs) {
        log.info("【{}】返回 {} 条", title, docs.size());
        for (Document d : docs) {
            log.info("  - {} (score={})", d.getMetadata().get("sourceRef"), d.getScore());
        }
    }

    private void demo(String title, String goal, List<String> tools) {
        ComplianceChecker.Result result = checker.check("jd", "apparel", goal, tools);
        log.info("【合规】{} tools={} query={} 召回={} passed={} violations={}",
                title, tools, result.query(), result.sources(), result.passed(), result.violations());
        for (ComplianceChecker.Finding f : result.findings()) {
            log.info("  - {} {} score={} satisfied={} {}",
                    f.sourceRef(), f.rule(), f.score(), f.satisfied(), f.detail());
        }
    }
}
