package io.github.zzzzain777.retouch.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.tool.ToolRegistry;
import io.github.zzzzain777.retouch.tool.ToolService;
import io.github.zzzzain777.retouch.tool.ToolSpec;

/**
 * 模型提议 → 计划。Java 独立校验，不信任模型输出：
 * 工具必须已登记 → 去掉由服务端填写的隐藏参数 → 走与手动接口同一套参数校验并规范化 →
 * 补全步骤 id 与依赖 → 步数、依赖存在性与环检测。任何一步不过，整份计划作废，不派发任何工具。
 */
@Component
public class PlanValidator {

    /** 提议不能成为计划；消息会拼进给用户的答复。 */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    private final ToolRegistry registry;
    private final ToolService tools;
    private final JsonMapper json;

    public PlanValidator(ToolRegistry registry, ToolService tools, JsonMapper json) {
        this.registry = registry;
        this.tools = tools;
        this.json = json;
    }

    public List<PlanStep> validate(List<Planner.Call> calls) {
        if (calls.size() > PlanRules.MAX_STEPS) {
            throw new Rejected("计划超过 " + PlanRules.MAX_STEPS + " 步，请拆成多次说明");
        }
        List<PlanStep> raw = new ArrayList<>();
        for (Planner.Call call : calls) {
            ToolSpec<?> spec = registry.find(call.tool())
                    .orElseThrow(() -> new Rejected("未注册的工具：" + call.tool()));
            Map<String, Object> params = arguments(call.arguments());
            spec.agentHidden().forEach(params::remove);
            ToolService.Checked checked;
            try {
                checked = tools.check(spec.name(), params);
            } catch (ApiException invalid) {
                throw new Rejected(invalid.getMessage());
            }
            raw.add(PlanStep.proposed(null, checked.tool(), checked.label(), checked.params(), null));
        }
        try {
            return PlanRules.assemble(raw);
        } catch (PlanRules.PlanError bad) {
            throw new Rejected(bad.getMessage());
        }
    }

    private Map<String, Object> arguments(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (JacksonException malformed) {
            throw new Rejected("工具参数格式不正确");
        }
    }
}
