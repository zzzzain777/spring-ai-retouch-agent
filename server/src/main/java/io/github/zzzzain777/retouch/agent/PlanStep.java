package io.github.zzzzain777.retouch.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 计划里的一步，整份计划以 JSON 存在 {@code agent_turns.plan}。
 *
 * @param label     界面文案，校验时按参数算好（如“水平翻转”）
 * @param params    校验并规范化后的参数，派发时原样提交
 * @param runId     最近一次派发的任务 id
 * @param approved  需要单独确认的工具已被用户放行
 * @param attempt   已派发次数；派发时用 attempt+1 作为 {@code tool_runs.step_attempt}，唯一约束防止重复派发
 */
public record PlanStep(String id, String tool, String label, Map<String, Object> params, List<String> dependsOn,
        UUID runId, StepStatus status, Boolean approved, Integer attempt) {

    public PlanStep {
        params = params == null ? Map.of() : params;
        status = status == null ? StepStatus.PENDING : status;
        approved = approved != null && approved;
        attempt = attempt == null ? 0 : attempt;
    }

    /** 校验前的提议：只有工具、参数与可选的依赖。 */
    public static PlanStep proposed(String id, String tool, String label, Map<String, Object> params,
            List<String> dependsOn) {
        return new PlanStep(id, tool, label, params, dependsOn, null, StepStatus.PENDING, false, 0);
    }

    public PlanStep withStatus(StepStatus next) {
        return new PlanStep(id, tool, label, params, dependsOn, runId, next, approved, attempt);
    }

    public PlanStep identified(String stepId, List<String> deps) {
        return new PlanStep(stepId, tool, label, params, deps, runId, status, approved, attempt);
    }

    public PlanStep dispatched(UUID run, StepStatus next, int dispatchAttempt) {
        return new PlanStep(id, tool, label, params, dependsOn, run, next, approved, dispatchAttempt);
    }

    public PlanStep approve() {
        return new PlanStep(id, tool, label, params, dependsOn, runId, StepStatus.PENDING, true, attempt);
    }

    /** 单步重试：清掉任务 id 回到未开始，派发次数保留，下次派发会新建一条任务。 */
    public PlanStep reset() {
        return new PlanStep(id, tool, label, params, dependsOn, null, StepStatus.PENDING, approved, attempt);
    }
}
