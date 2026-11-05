package io.github.zzzzain777.retouch.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.zzzzain777.retouch.tool.RunStatus;

/** 对外的轮次与步骤视图，字段与上游 TurnOut / PlanStepOut 一致；参数与派发次数不对外。 */
public final class AgentViews {

    private AgentViews() {
    }

    public record MessageIn(String text) {
    }

    public record StepOut(String id, String tool, String label, List<String> dependsOn, UUID runId,
            StepStatus status) {

        static StepOut of(PlanStep step) {
            return new StepOut(step.id(), step.tool(), step.label(), step.dependsOn(), step.runId(), step.status());
        }
    }

    public record TurnOut(UUID id, int revision, String goal, String reply, RunStatus status, String error,
            Instant createdAt, List<StepOut> steps) {

        static TurnOut of(AgentTurn turn, List<PlanStep> steps) {
            return new TurnOut(turn.getId(), turn.getRevision(), turn.getGoal(), turn.getReply(), turn.getStatus(),
                    turn.getError(), turn.getCreatedAt(), steps.stream().map(StepOut::of).toList());
        }
    }
}
