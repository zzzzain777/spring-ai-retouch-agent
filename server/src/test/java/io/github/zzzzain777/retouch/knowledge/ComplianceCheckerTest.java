package io.github.zzzzain777.retouch.knowledge;

import java.util.List;
import java.util.Map;

import io.github.zzzzain777.retouch.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 合规检查器单测：只验证「检索词 → 召回条款 → 规则 → 计划」的判定逻辑，
 * 不启动 Spring、不连数据库、不调模型（检索结果由 Mockito 给定）。
 */
class ComplianceCheckerTest {

    private static final String GOAL = "把这张图做成京东服饰首图";

    private KnowledgeRetriever retriever;
    private KnowledgeIndexService indexService;
    private ComplianceChecker checker;

    private static Document clause(String ref, String text) {
        // 单测里不设 score：相似度由向量库填，这里只验证判定逻辑
        return new Document(text, Map.of(
                "sourceRef", ref,
                "platform", "jd",
                "category", "apparel"));
    }

    /** 背景与文案两条，正是「做首图」这个目标该召回的条款。 */
    private static List<Document> backgroundAndTextClauses() {
        return List.of(
                clause("JD-APPAREL-001#rule-1", "rule-1 背景\n首图背景必须为纯白（#FFFFFF），不得出现渐变、阴影或场景图。"),
                clause("JD-APPAREL-001#rule-3", "rule-3 文案\n首图不得出现促销文案、水印、拼接或边框。"));
    }

    private static ComplianceRuleProperties defaultRules() {
        return new ComplianceRuleProperties(List.of(
                new ComplianceRuleProperties.Rule("background-white", "背景必须纯白",
                        List.of("纯白", "白底", "白色背景"),
                        List.of("remove_background", "replace_background"), List.of()),
                new ComplianceRuleProperties.Rule("no-text", "主图不得出现文字/促销元素",
                        List.of("促销", "水印", "文字"),
                        List.of(), List.of("set_layer_text"))));
    }

    private void withRules(ComplianceRuleProperties properties) {
        checker = new ComplianceChecker(retriever, indexService, new ToolRegistry(), properties);
    }

    private void retrieves(List<Document> docs) {
        when(retriever.searchForCompliance(eq("jd"), eq("apparel"), any())).thenReturn(docs);
    }

    @BeforeEach
    void setUp() {
        retriever = mock(KnowledgeRetriever.class);
        indexService = mock(KnowledgeIndexService.class);
        withRules(defaultRules());
    }

    @Test
    void queryCombinesGoalAndToolLabels() {
        String query = checker.query(GOAL, List.of("remove_background", "set_layer_text"));

        assertThat(query).startsWith(GOAL).contains("计划操作：");
        // 工具中文名来自 ToolRegistry，与手动接口、模型 Schema 同一份规格
        assertThat(query).contains("去背景").contains("改文字");
    }

    @Test
    void missingBackgroundToolIsAViolation() {
        retrieves(backgroundAndTextClauses());

        var result = checker.check("jd", "apparel", GOAL, List.of("flip_layer"));

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("背景必须纯白"));
        assertThat(result.sources()).containsExactly("JD-APPAREL-001#rule-1", "JD-APPAREL-001#rule-3");
    }

    @Test
    void removingBackgroundPasses() {
        retrieves(backgroundAndTextClauses());

        var result = checker.check("jd", "apparel", GOAL, List.of("remove_background"));

        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.findings()).hasSize(2).allSatisfy(f -> assertThat(f.satisfied()).isTrue());
    }

    @Test
    void addingTextIsAViolation() {
        retrieves(backgroundAndTextClauses());

        var result = checker.check("jd", "apparel", GOAL, List.of("remove_background", "set_layer_text"));

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("主图不得出现文字"));
    }

    @Test
    void emptyRecallIsNotPassed() {
        retrieves(List.of());

        var result = checker.check("jd", "apparel", "帮我写一首关于春天的诗", List.of("remove_background"));

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("没有检索到适用规范"));
    }

    @Test
    void onlyRecalledClausesAreChecked() {
        // 只召回文案条款时，背景规则不参与判定：召回不足等于漏检，这是设计上的已知代价
        retrieves(List.of(clause("JD-APPAREL-001#rule-3", "rule-3 文案\n首图不得出现促销文案、水印、拼接或边框。")));

        var result = checker.check("jd", "apparel", GOAL, List.of("flip_layer"));

        assertThat(result.passed()).isTrue();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).rule()).isEqualTo("主图不得出现文字/促销元素");
    }

    @Test
    void noMatchingRuleIsNotPassed() {
        retrieves(backgroundAndTextClauses());
        withRules(new ComplianceRuleProperties(List.of(
                new ComplianceRuleProperties.Rule("unrelated", "无关规则",
                        List.of("不存在的关键词"), List.of(), List.of()))));

        var result = checker.check("jd", "apparel", GOAL, List.of("remove_background"));

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("没有匹配的检查规则"));
    }
}
