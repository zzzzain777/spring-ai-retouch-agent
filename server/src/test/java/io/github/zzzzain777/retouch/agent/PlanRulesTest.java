package io.github.zzzzain777.retouch.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.zzzzain777.retouch.agent.PlanRules.PlanError;
import io.github.zzzzain777.retouch.tool.RunStatus;

/** 计划结构规则：步骤编号、默认串行依赖、缺失依赖、环、步数上限，以及就绪判断与状态汇总。 */
class PlanRulesTest {

    private static PlanStep step(String id, List<String> deps) {
        return PlanStep.proposed(id, "flip_layer", "翻转", Map.of("direction", "horizontal"), deps);
    }

    private static PlanStep at(PlanStep step, StepStatus status) {
        return step.dispatched(status.undispatched() ? null : UUID.randomUUID(), status, 1);
    }

    @Test
    void numbersStepsAndChainsThemByDefault() {
        var steps = PlanRules.assemble(List.of(step(null, null), step(null, null), step(null, null)));
        assertThat(steps).extracting(PlanStep::id).containsExactly("s1", "s2", "s3");
        assertThat(steps).extracting(PlanStep::dependsOn).containsExactly(List.of(), List.of("s1"), List.of("s2"));
        assertThat(steps).extracting(PlanStep::status).containsOnly(StepStatus.PENDING);
    }

    @Test
    void keepsExplicitDependencies() {
        var steps = PlanRules.assemble(List.of(step("a", List.of()), step("b", List.of()), step("c", List.of("a", "b"))));
        assertThat(steps.get(1).dependsOn()).isEmpty();
        assertThat(steps.get(2).dependsOn()).containsExactly("a", "b");
    }

    @Test
    void rejectsMissingDependency() {
        assertThatThrownBy(() -> PlanRules.assemble(List.of(step("a", List.of("x")))))
                .isInstanceOf(PlanError.class).hasMessage("步骤 a 依赖了不存在的步骤");
    }

    @Test
    void rejectsCyclesIncludingSelfDependency() {
        assertThatThrownBy(() -> PlanRules.assemble(List.of(step("a", List.of("b")), step("b", List.of("a")))))
                .isInstanceOf(PlanError.class).hasMessage("计划步骤存在循环依赖");
        assertThatThrownBy(() -> PlanRules.assemble(List.of(step("a", List.of("a")))))
                .isInstanceOf(PlanError.class).hasMessage("计划步骤存在循环依赖");
        assertThatThrownBy(() -> PlanRules.assemble(
                List.of(step("a", List.of()), step("b", List.of("a", "c")), step("c", List.of("b")))))
                .hasMessage("计划步骤存在循环依赖");
    }

    @Test
    void rejectsDuplicateIds() {
        assertThatThrownBy(() -> PlanRules.assemble(List.of(step("a", List.of()), step("a", List.of()))))
                .hasMessage("计划步骤编号重复：a");
    }

    @Test
    void capsPlansAtEightSteps() {
        List<PlanStep> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(step(null, null));
        }
        assertThat(PlanRules.assemble(eight)).hasSize(8);
        eight.add(step(null, null));
        assertThatThrownBy(() -> PlanRules.assemble(eight)).hasMessage("计划超过 8 步，请拆成多次说明");
    }

    @Test
    void readyNeedsEverySucceededDependency() {
        var steps = PlanRules.assemble(List.of(step(null, null), step(null, null)));
        assertThat(PlanRules.ready(steps)).extracting(PlanStep::id).containsExactly("s1");

        var running = List.of(at(steps.get(0), StepStatus.RUNNING), steps.get(1));
        assertThat(PlanRules.ready(running)).isEmpty();

        var done = List.of(at(steps.get(0), StepStatus.SUCCEEDED), steps.get(1));
        assertThat(PlanRules.ready(done)).extracting(PlanStep::id).containsExactly("s2");

        var failed = List.of(at(steps.get(0), StepStatus.FAILED), steps.get(1));
        assertThat(PlanRules.ready(failed)).isEmpty();
    }

    @Test
    void settlesLikeUpstream() {
        var steps = PlanRules.assemble(List.of(step(null, null), step(null, null)));
        assertThat(PlanRules.settle(List.of(at(steps.get(0), StepStatus.QUEUED), steps.get(1))))
                .isEqualTo(RunStatus.RUNNING);
        assertThat(PlanRules.settle(List.of(at(steps.get(0), StepStatus.SUCCEEDED), steps.get(1).withStatus(
                StepStatus.WAITING)))).isEqualTo(RunStatus.QUEUED);
        assertThat(PlanRules.settle(List.of(at(steps.get(0), StepStatus.SUCCEEDED),
                at(steps.get(1), StepStatus.SUCCEEDED)))).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(PlanRules.settle(PlanRules.cancelRemaining(steps))).isEqualTo(RunStatus.CANCELED);
        assertThat(PlanRules.settle(List.of(at(steps.get(0), StepStatus.SUCCEEDED),
                at(steps.get(1), StepStatus.FAILED)))).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void failedStepBlocksItsDependentsAndTheTurnSettlesAsFailed() {
        var steps = PlanRules.assemble(List.of(step(null, null), step(null, null), step(null, null)));
        var blocked = List.of(at(steps.get(0), StepStatus.FAILED), steps.get(1), steps.get(2));
        // 上游这里会一直是 running；后续步骤永远不会执行，应汇总为 failed
        assertThat(PlanRules.settle(blocked)).isEqualTo(RunStatus.FAILED);
        assertThat(PlanRules.unreachable(blocked)).containsExactlyInAnyOrder("s2", "s3");
    }

    @Test
    void cancelOnlyTouchesUndispatchedSteps() {
        var steps = PlanRules.assemble(List.of(step(null, null), step(null, null), step(null, null)));
        var mixed = List.of(at(steps.get(0), StepStatus.RUNNING), steps.get(1).withStatus(StepStatus.WAITING),
                steps.get(2));
        assertThat(PlanRules.cancelRemaining(mixed)).extracting(PlanStep::status)
                .containsExactly(StepStatus.RUNNING, StepStatus.CANCELED, StepStatus.CANCELED);
        assertThat(PlanRules.settle(PlanRules.cancelRemaining(mixed))).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void multiStepPlansNeedConfirmation() {
        assertThat(PlanRules.needsConfirm(List.of(step("a", List.of())))).isFalse();
        assertThat(PlanRules.needsConfirm(List.of(step("a", List.of()), step("b", List.of("a"))))).isTrue();
    }

    @Test
    void repliesFollowThePlan() {
        var one = PlanRules.assemble(List.of(PlanStep.proposed(null, "flip_layer", "水平翻转", Map.of(), null)));
        var two = PlanRules.assemble(List.of(PlanStep.proposed(null, "flip_layer", "水平翻转", Map.of(), null),
                PlanStep.proposed(null, "adjust_image", "调色", Map.of(), null)));
        assertThat(AgentService.spoken("", one)).isEqualTo("好，正在水平翻转。");
        assertThat(AgentService.spoken("", two)).isEqualTo("将按以下步骤执行：水平翻转、调色。确认后开始。");
        assertThat(AgentService.spoken("", List.of())).isEqualTo(AgentService.FALLBACK_REPLY);
        assertThat(AgentService.spoken("这个做不到。可以试试别的说法。", List.of())).isEqualTo("这个做不到。");
        assertThat(AgentService.spoken("做不到！真的", List.of())).isEqualTo("做不到！");
        assertThat(AgentService.firstSentence("a".repeat(80))).hasSize(61).endsWith("…");
    }
}
