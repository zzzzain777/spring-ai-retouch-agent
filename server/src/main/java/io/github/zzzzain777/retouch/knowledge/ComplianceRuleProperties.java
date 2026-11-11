package io.github.zzzzain777.retouch.knowledge;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 合规规则表：把「规范条款里的关键词」映射成「计划必须包含 / 禁止包含的工具」。
 *
 * <p>规则写在 application.yml 的 retouch.knowledge.compliance.rules 下，
 * 新增平台/类目的检查项不需要改代码。
 */
@ConfigurationProperties("retouch.knowledge.compliance")
public record ComplianceRuleProperties(List<Rule> rules) {

    public record Rule(
            String id,
            String message,
            List<String> keywords,
            List<String> requireAny,
            List<String> forbid) {

        public boolean matches(String text) {
            return keywords != null && keywords.stream().anyMatch(text::contains);
        }
    }

    public List<Rule> rulesOrEmpty() {
        return rules == null ? List.of() : rules;
    }
}
