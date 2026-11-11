package io.github.zzzzain777.retouch.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.zzzzain777.retouch.tool.ToolRegistry;
import io.github.zzzzain777.retouch.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * 合规检查器：先按「这次要做什么」检索适用条款，再把条款变成对计划的硬校验。
 *
 * <p>检索词由本轮目标和计划里的工具中文名拼成，所以召回的是与本次场景相关的条款，
 * 而不是该平台类目下的全部条款；语料变大以后不必每次全量扫。
 * 代价是漏召回等于漏检，因此空召回不放行（见 {@code docs/knowledge-rag.md} 的边界一节）。
 *
 * <p>规则来自 {@link ComplianceRuleProperties}（application.yml 配置），
 * 检查器本身不写死任何关键词或工具名。检索不到规范、或没有匹配的规则时，都判为不通过交人工。
 */
@Service
public class ComplianceChecker {

    private static final Logger log = LoggerFactory.getLogger(ComplianceChecker.class);

    public record Finding(String sourceRef, String clause, Double score, String rule, boolean satisfied,
            String detail) {
    }

    public record Result(boolean passed, String query, List<String> sources, List<Finding> findings,
            List<String> violations) {
    }

    private final KnowledgeRetriever retriever;
    private final KnowledgeIndexService indexService;
    private final ToolRegistry registry;
    private final List<ComplianceRuleProperties.Rule> rules;

    public ComplianceChecker(KnowledgeRetriever retriever, KnowledgeIndexService indexService,
                             ToolRegistry registry, ComplianceRuleProperties properties) {
        this.retriever = retriever;
        this.indexService = indexService;
        this.registry = registry;
        this.rules = properties.rulesOrEmpty();
    }

    /**
     * @param goal      本轮目标，用户原话（例如「把这张图做成京东服饰首图」）
     * @param toolNames 计划里要执行的工具名（按顺序）
     */
    public Result check(String platform, String category, String goal, List<String> toolNames) {
        indexService.ensureIndexed();

        String query = query(goal, toolNames);
        List<Document> docs = retriever.searchForCompliance(platform, category, query);
        List<String> sources = docs.stream().map(ComplianceChecker::ref).toList();

        if (docs.isEmpty()) {
            return new Result(false, query, sources, List.of(), List.of("没有检索到适用规范，需人工核对"));
        }
        if (rules.isEmpty()) {
            return new Result(false, query, sources, List.of(), List.of("没有配置合规规则，需人工核对"));
        }

        Set<String> tools = Set.copyOf(toolNames);
        List<Finding> findings = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (Document doc : docs) {
            String text = doc.getText();
            String ref = ref(doc);
            for (ComplianceRuleProperties.Rule rule : rules) {
                if (!rule.matches(text)) {
                    continue;
                }
                boolean ok = true;
                String detail = "满足";
                if (rule.requireAny() != null && !rule.requireAny().isEmpty()) {
                    ok = rule.requireAny().stream().anyMatch(tools::contains);
                    detail = ok ? "计划包含所需工具" : "计划缺少 " + String.join(" / ", rule.requireAny());
                }
                if (rule.forbid() != null) {
                    for (String forbidden : rule.forbid()) {
                        if (tools.contains(forbidden)) {
                            ok = false;
                            detail = "计划包含禁止的工具 " + forbidden;
                            break;
                        }
                    }
                }
                findings.add(new Finding(ref, clause(text), doc.getScore(), rule.message(), ok, detail));
                if (!ok) {
                    violations.add(ref + "：" + rule.message() + "（" + detail + "）");
                }
            }
        }

        if (findings.isEmpty()) {
            return new Result(false, query, sources, List.of(),
                    List.of("检索到 " + docs.size() + " 条规范，但没有匹配的检查规则，需人工核对"));
        }

        boolean passed = violations.isEmpty();
        log.info("合规检查 platform={} category={} query={} 召回={} tools={} passed={} violations={}",
                platform, category, query, sources, tools, passed, violations);
        return new Result(passed, query, sources, List.copyOf(findings), List.copyOf(violations));
    }

    /** 检索词：本轮目标 + 计划里工具的中文名，让召回贴近这次真正要做的事。 */
    String query(String goal, List<String> toolNames) {
        Set<String> labels = toolNames.stream()
                .map(name -> registry.find(name).map(ToolSpec::label).orElse(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String goalText = goal == null ? "" : goal.strip();
        if (labels.isEmpty()) {
            return goalText;
        }
        return (goalText.isEmpty() ? "" : goalText + "；") + "计划操作：" + String.join("、", labels);
    }

    private static String ref(Document doc) {
        return String.valueOf(doc.getMetadata().get("sourceRef"));
    }

    /** 条款首行即标题，返回它便于在响应里指明命中的是哪一条。 */
    private static String clause(String text) {
        int nl = text.indexOf('\n');
        return nl > 0 ? text.substring(0, nl).strip() : text.strip();
    }
}
