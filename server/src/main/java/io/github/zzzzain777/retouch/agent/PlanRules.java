package io.github.zzzzain777.retouch.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.zzzzain777.retouch.tool.RunStatus;

/**
 * 计划的纯规则：组装与结构校验、就绪判断、状态汇总、取消。不碰数据库，便于单测。
 * 语义与上游 {@code agent/plan.py} 对齐，差异见各方法说明。
 */
public final class PlanRules {

    public static final int MAX_STEPS = 8;

    /** 计划结构不合法（超长、依赖缺失、成环）。 */
    public static class PlanError extends RuntimeException {
        public PlanError(String message) {
            super(message);
        }
    }

    private PlanRules() {
    }

    /**
     * 补全步骤 id（s1、s2…）与依赖：没给依赖的步骤依赖前一步，第一步无依赖。
     * 拒绝超过 {@value #MAX_STEPS} 步、编号重复、依赖不存在的步骤、循环依赖（含自依赖）。
     */
    public static List<PlanStep> assemble(List<PlanStep> raw) {
        if (raw.size() > MAX_STEPS) {
            throw new PlanError("计划超过 " + MAX_STEPS + " 步，请拆成多次说明");
        }
        List<PlanStep> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            PlanStep item = raw.get(i);
            String id = item.id() == null || item.id().isBlank() ? "s" + (i + 1) : item.id();
            if (!seen.add(id)) {
                throw new PlanError("计划步骤编号重复：" + id);
            }
            List<String> deps = item.dependsOn() != null ? List.copyOf(item.dependsOn())
                    : steps.isEmpty() ? List.of() : List.of(steps.getLast().id());
            steps.add(item.identified(id, deps));
        }
        for (PlanStep step : steps) {
            for (String dep : step.dependsOn()) {
                if (!seen.contains(dep)) {
                    throw new PlanError("步骤 " + step.id() + " 依赖了不存在的步骤");
                }
            }
        }
        if (cyclic(steps)) {
            throw new PlanError("计划步骤存在循环依赖");
        }
        return steps;
    }

    /** 可以派发的步骤：未开始、未派发过当前这一次，且所有依赖都已成功。 */
    public static List<PlanStep> ready(List<PlanStep> steps) {
        Map<String, PlanStep> byId = index(steps);
        List<PlanStep> ready = new ArrayList<>();
        for (PlanStep step : steps) {
            if (step.status().undispatched() && step.runId() == null
                    && step.dependsOn().stream().allMatch(dep -> byId.get(dep).status() == StepStatus.SUCCEEDED)) {
                ready.add(step);
            }
        }
        return ready;
    }

    /**
     * 汇总整轮状态：还有能推进的步骤时，只剩待确认的为 queued，否则 running；
     * 全部结束后有失败为 failed，有取消为 canceled，否则 succeeded。
     * <p>
     * 与上游的差异：上游把“依赖已失败、永远不会执行的未开始步骤”也算作未完成，
     * 导致前面一步失败时整轮一直停在 running、前端一直轮询。这里把这类步骤视为已阻断，整轮按失败汇总，
     * 单步重试后它们仍会继续执行。
     */
    public static RunStatus settle(List<PlanStep> steps) {
        Set<String> stuck = unreachable(steps);
        boolean inFlight = false;
        boolean waiting = false;
        boolean open = false;
        for (PlanStep step : steps) {
            if (step.status().inFlight()) {
                inFlight = true;
                open = true;
            } else if (step.status().undispatched() && !stuck.contains(step.id())) {
                open = true;
                waiting |= step.status() == StepStatus.WAITING;
            }
        }
        if (open) {
            return waiting && !inFlight ? RunStatus.QUEUED : RunStatus.RUNNING;
        }
        if (steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED)) {
            return RunStatus.FAILED;
        }
        if (steps.stream().anyMatch(step -> step.status() == StepStatus.CANCELED)) {
            return RunStatus.CANCELED;
        }
        return RunStatus.SUCCEEDED;
    }

    /** 多步计划先停下等用户确认；单步直接执行。 */
    public static boolean needsConfirm(List<PlanStep> steps) {
        return steps.size() > 1;
    }

    /** 取消：未开始与待确认的步骤标为取消；已派发的步骤由调用方去取消对应任务。 */
    public static List<PlanStep> cancelRemaining(List<PlanStep> steps) {
        return steps.stream()
                .map(step -> step.status().undispatched() ? step.withStatus(StepStatus.CANCELED) : step)
                .toList();
    }

    /** 未开始、但某个（直接或间接）依赖已失败或取消的步骤。 */
    static Set<String> unreachable(List<PlanStep> steps) {
        Map<String, PlanStep> byId = index(steps);
        Map<String, Boolean> memo = new HashMap<>();
        Set<String> stuck = new HashSet<>();
        for (PlanStep step : steps) {
            if (step.status().undispatched() && blocked(step, byId, memo)) {
                stuck.add(step.id());
            }
        }
        return stuck;
    }

    private static boolean blocked(PlanStep step, Map<String, PlanStep> byId, Map<String, Boolean> memo) {
        Boolean known = memo.get(step.id());
        if (known != null) {
            return known;
        }
        memo.put(step.id(), false); // 结构已保证无环，这里只防御
        boolean result = false;
        for (String dep : step.dependsOn()) {
            PlanStep parent = byId.get(dep);
            if (parent.status().blocking() || (parent.status().undispatched() && blocked(parent, byId, memo))) {
                result = true;
                break;
            }
        }
        memo.put(step.id(), result);
        return result;
    }

    private static Map<String, PlanStep> index(List<PlanStep> steps) {
        Map<String, PlanStep> byId = new LinkedHashMap<>();
        steps.forEach(step -> byId.put(step.id(), step));
        return byId;
    }

    /** Kahn 拓扑排序：能排完所有节点就无环。 */
    private static boolean cyclic(List<PlanStep> steps) {
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (PlanStep step : steps) {
            incoming.put(step.id(), step.dependsOn().size());
            outgoing.putIfAbsent(step.id(), new ArrayList<>());
        }
        for (PlanStep step : steps) {
            for (String dep : step.dependsOn()) {
                outgoing.get(dep).add(step.id());
            }
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        incoming.forEach((id, count) -> {
            if (count == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            visited++;
            for (String next : outgoing.get(node)) {
                if (incoming.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        return visited != steps.size();
    }
}
