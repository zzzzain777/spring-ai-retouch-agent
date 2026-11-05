package io.github.zzzzain777.retouch.agent;

import java.util.List;

/**
 * 一次规划调用的执行轨迹，存在 {@code agent_turns.trace}：评测与排查时对照模型到底提议了什么。
 * 不含密钥与完整提示词。
 */
public record PlannerTrace(String model, Long elapsedMs, Integer promptTokens, Integer completionTokens,
        String finishReason, Integer toolsOffered, List<Planner.Call> toolCalls, String text, String rejected,
        String failure) {

    public PlannerTrace withRejected(String reason) {
        return new PlannerTrace(model, elapsedMs, promptTokens, completionTokens, finishReason, toolsOffered,
                toolCalls, text, reason, failure);
    }

    public static PlannerTrace failed(String model, long elapsedMs, String failure) {
        return new PlannerTrace(model, elapsedMs, null, null, null, null, List.of(), null, null, failure);
    }
}
