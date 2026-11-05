package io.github.zzzzain777.retouch.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.selection.SelectionStore;
import io.github.zzzzain777.retouch.session.EditSession;
import io.github.zzzzain777.retouch.session.SessionService;
import io.github.zzzzain777.retouch.tool.RunListener;
import io.github.zzzzain777.retouch.tool.RunStatus;
import io.github.zzzzain777.retouch.tool.ToolRegistry;
import io.github.zzzzain777.retouch.tool.ToolRun;
import io.github.zzzzain777.retouch.tool.ToolRunRepository;
import io.github.zzzzain777.retouch.tool.ToolService;
import io.github.zzzzain777.retouch.tool.ToolService.StepRef;
import io.github.zzzzain777.retouch.tool.ToolSpec;

/**
 * 一轮指令的完整生命周期：规划 → 校验 → 落库 →（多步先等确认）→ 由 Java 显式派发每一步 → 汇总状态。
 * <p>
 * 并发与恢复：
 * <ul>
 * <li>确认、取消、重试、推进都在锁住轮次行的事务里做，同一轮串行；</li>
 * <li>派发经 {@link ToolService#submit}（独立事务），任务带上 (turn_id, step_id, step_attempt)，库里唯一约束防重复派发；</li>
 * <li>步骤状态以 tool_runs 为准：每次推进先按派发次数对账，进程在“任务已提交、计划未更新”之间崩溃也能接上；</li>
 * <li>图像任务结束后由 Worker 回调 {@link #finished}；回调丢了还有定时对账兜底。</li>
 * </ul>
 */
@Service
public class AgentService implements RunListener {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    static final int TURN_LIMIT = 50;
    static final String FALLBACK_REPLY = "没太理解这条指令，换个说法或说得更具体一些。";
    static final int REPLY_LIMIT = 60;
    private static final Duration RECONCILE_AFTER = Duration.ofSeconds(30);

    /** 新建或按幂等键找回的轮次。 */
    public record Outcome(AgentTurn turn, boolean replayed) {
    }

    private final AgentTurnRepository turns;
    private final ToolRunRepository runs;
    private final SessionService sessions;
    private final CanvasSummary canvas;
    private final Planner planner;
    private final PlanValidator validator;
    private final ToolService tools;
    private final ToolRegistry registry;
    private final SelectionStore selections;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public AgentService(AgentTurnRepository turns, ToolRunRepository runs, SessionService sessions,
            CanvasSummary canvas, Planner planner, PlanValidator validator, ToolService tools, ToolRegistry registry,
            SelectionStore selections, PlatformTransactionManager transactions, JsonMapper json) {
        this.selections = selections;
        this.turns = turns;
        this.runs = runs;
        this.sessions = sessions;
        this.canvas = canvas;
        this.planner = planner;
        this.validator = validator;
        this.tools = tools;
        this.registry = registry;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
    }

    // ---- 对外操作 ----

    /**
     * 规划一轮指令并落库：多步计划停在 queued 等确认，单步直接开始执行，无计划时给一句答复。
     * 规划调用在事务外进行，不占数据库连接。同一会话同一幂等键只落一轮、只执行一次。
     */
    public Outcome respond(UUID userId, UUID sessionId, String goal, String idempotencyKey) {
        EditSession record = sessions.get(userId, sessionId);
        if (idempotencyKey != null) {
            var existing = turns.findBySessionIdAndIdempotencyKey(sessionId, idempotencyKey);
            if (existing.isPresent()) {
                return new Outcome(existing.get(), true);
            }
        }

        String summary = canvas.describe(record);
        String reply = "";
        String error = null;
        List<PlanStep> steps = List.of();
        PlannerTrace trace = null;
        long started = System.nanoTime();
        try {
            Planner.Proposal proposal = planner.plan(goal, summary);
            trace = proposal.trace();
            reply = proposal.text();
            try {
                steps = pinSelection(record, validator.validate(proposal.calls()));
            } catch (PlanValidator.Rejected rejected) {
                steps = List.of();
                reply = "这一步暂时执行不了：" + rejected.getMessage();
                trace = trace == null ? null : trace.withRejected(rejected.getMessage());
            }
            reply = spoken(reply, steps);
        } catch (Planner.Unavailable unavailable) {
            error = unavailable.getMessage();
        } catch (RuntimeException failure) {
            log.error("指令规划异常 session_id={}", sessionId, failure);
            error = "规划失败，请重试";
            trace = PlannerTrace.failed(null, Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    failure.getClass().getSimpleName());
        }

        RunStatus status = error != null ? RunStatus.FAILED
                : PlanRules.needsConfirm(steps) ? RunStatus.QUEUED
                : steps.isEmpty() ? RunStatus.SUCCEEDED : RunStatus.RUNNING;
        var turn = new AgentTurn(userId, sessionId, record.getRevision(), goal, idempotencyKey);
        turn.reply(reply);
        turn.error(error);
        turn.update(write(steps), status);
        if (trace != null) {
            turn.trace(json.writeValueAsString(trace));
        }
        try {
            tx.executeWithoutResult(s -> turns.saveAndFlush(turn));
        } catch (DataIntegrityViolationException duplicate) {
            // 同一幂等键的并发请求：先落库的那一轮为准，这一份计划丢弃、不执行
            if (idempotencyKey != null) {
                var winner = turns.findBySessionIdAndIdempotencyKey(sessionId, idempotencyKey);
                if (winner.isPresent()) {
                    return new Outcome(winner.get(), true);
                }
            }
            throw duplicate;
        }
        if (status == RunStatus.RUNNING) {
            advance(turn.getId());
        }
        return new Outcome(turns.findById(turn.getId()).orElseThrow(), false);
    }

    /** 放行待确认的步骤并开始执行。规划后画布被改过（修订号变了）就拒绝执行旧计划。 */
    public AgentTurn confirm(UUID userId, UUID sessionId, UUID turnId) {
        sessions.get(userId, sessionId);
        find(sessionId, turnId);
        return tx.execute(s -> {
            AgentTurn turn = turns.lockById(turnId).orElseThrow();
            List<PlanStep> steps = read(turn);
            boolean waiting = steps.stream().anyMatch(step -> step.status() == StepStatus.WAITING);
            if (turn.getStatus() != RunStatus.QUEUED && !waiting) {
                throw ApiException.conflict("当前没有待确认的步骤");
            }
            boolean untouched = steps.stream().allMatch(step -> step.runId() == null && step.attempt() == 0);
            if (untouched && sessions.get(userId, sessionId).getRevision() != turn.getRevision()) {
                throw ApiException.conflict("画布在等待确认期间已被修改，请重新发送指令");
            }
            steps = steps.stream().map(step -> step.status() == StepStatus.WAITING ? step.approve() : step).toList();
            turn.update(write(steps), RunStatus.RUNNING);
            advanceLocked(turn);
            return turn;
        });
    }

    /**
     * 取消：未开始的步骤标为取消，排队中的任务直接取消；运行中的任务发出取消请求，
     * Worker 在下一个检查点结束并丢弃远程结果，轮次在它结束后汇总为 canceled。
     */
    public AgentTurn cancel(UUID userId, UUID sessionId, UUID turnId) {
        sessions.get(userId, sessionId);
        find(sessionId, turnId);
        return tx.execute(s -> {
            AgentTurn turn = turns.lockById(turnId).orElseThrow();
            if (turn.getStatus().terminal()) {
                throw ApiException.conflict("当前没有可取消的步骤");
            }
            List<PlanStep> steps = new ArrayList<>(PlanRules.cancelRemaining(reconcile(turn, read(turn))));
            for (int i = 0; i < steps.size(); i++) {
                PlanStep step = steps.get(i);
                if (step.runId() != null && step.status().inFlight()) {
                    ToolRun run = tools.requestCancel(step.runId());
                    steps.set(i, step.withStatus(StepStatus.of(run.getStatus())));
                }
            }
            turn.update(write(steps), PlanRules.settle(steps));
            return turn;
        });
    }

    /** 单步重试：只重置最后一个失败的步骤，新建任务重新执行，再按依赖继续后面的步骤。 */
    public AgentTurn retry(UUID userId, UUID sessionId, UUID turnId) {
        sessions.get(userId, sessionId);
        find(sessionId, turnId);
        return tx.execute(s -> {
            AgentTurn turn = turns.lockById(turnId).orElseThrow();
            List<PlanStep> steps = new ArrayList<>(reconcile(turn, read(turn)));
            int failed = -1;
            for (int i = steps.size() - 1; i >= 0; i--) {
                if (steps.get(i).status() == StepStatus.FAILED) {
                    failed = i;
                    break;
                }
            }
            if (failed < 0) {
                throw ApiException.conflict("没有失败的步骤可重试");
            }
            steps.set(failed, steps.get(failed).reset());
            turn.error(null);
            turn.update(write(steps), RunStatus.RUNNING);
            advanceLocked(turn);
            return turn;
        });
    }

    public List<AgentTurn> turns(UUID userId, UUID sessionId) {
        sessions.get(userId, sessionId);
        return turns.findBySessionIdOrderByCreatedAtAsc(sessionId, Limit.of(TURN_LIMIT));
    }

    public AgentTurn turn(UUID userId, UUID sessionId, UUID turnId) {
        sessions.get(userId, sessionId);
        return find(sessionId, turnId);
    }

    public List<PlanStep> steps(AgentTurn turn) {
        return read(turn);
    }

    /**
     * 把规划时的选区遮罩钉进每个需要遮罩的步骤。第一步执行后修订号就变了、选区随之失效，
     * 显式的遮罩 id 让同一轮的后续步骤仍作用在用户选的那块区域上。
     */
    private List<PlanStep> pinSelection(EditSession record, List<PlanStep> steps) {
        var stored = selections.get(record.getId(), record.getRevision());
        if (stored.isEmpty()) {
            return steps;
        }
        String mask = stored.get().maskAssetId().toString();
        return steps.stream().map(step -> {
            boolean wantsMask = registry.find(step.tool()).map(ToolSpec::usesMask).orElse(false);
            if (!wantsMask || step.params().get("mask_asset_id") != null) {
                return step;
            }
            var params = new java.util.LinkedHashMap<>(step.params());
            params.put("mask_asset_id", mask);
            return new PlanStep(step.id(), step.tool(), step.label(), params, step.dependsOn(), step.runId(),
                    step.status(), step.approved(), step.attempt());
        }).toList();
    }

    // ---- 推进 ----

    /** Worker 跑完一个图像任务后回调：推进它所在的计划。 */
    @Override
    public void finished(ToolRun run) {
        if (run.getTurnId() != null) {
            advance(run.getTurnId());
        }
    }

    /** 启动时把仍在执行的轮次全部对账一次：重启前已结束的任务，其后续步骤在这里接着派发。 */
    @EventListener(ApplicationReadyEvent.class)
    void resumeOnStartup() {
        try {
            reconcileStale(Instant.now());
        } catch (RuntimeException failure) {
            // 数据库暂时不可用不应阻止启动，定时对账会再试
            log.error("启动时计划对账失败", failure);
        }
    }

    /** 兜底：回调丢失（回调异常、进程在提交后崩溃）时，定时对账超过 30 秒没更新的执行中轮次。 */
    @Scheduled(fixedDelayString = "${retouch.agent.reconcile-interval:30s}",
            initialDelayString = "${retouch.agent.reconcile-interval:30s}")
    void reconcileStale() {
        try {
            reconcileStale(Instant.now().minus(RECONCILE_AFTER));
        } catch (RuntimeException failure) {
            log.error("定时计划对账失败", failure);
        }
    }

    private void reconcileStale(Instant before) {
        for (UUID id : turns.idsByStatusBefore(RunStatus.RUNNING, before, Limit.of(50))) {
            try {
                advance(id);
            } catch (RuntimeException failure) {
                log.error("计划对账失败 turn_id={}", id, failure);
            }
        }
    }

    void advance(UUID turnId) {
        tx.executeWithoutResult(s -> turns.lockById(turnId).ifPresent(this::advanceLocked));
    }

    /**
     * 必须在锁住轮次行的事务里调用。先按 tool_runs 对账，执行中的轮次再派发就绪步骤，
     * 同步完成的步骤（文档工具）完成后立即继续下一步，最后汇总状态。
     */
    private void advanceLocked(AgentTurn turn) {
        List<PlanStep> steps = reconcile(turn, read(turn));
        if (turn.getStatus() != RunStatus.RUNNING) {
            turn.update(write(steps), turn.getStatus());
            return;
        }
        steps = dispatchReady(turn, steps);
        RunStatus next = PlanRules.settle(steps);
        if (next == RunStatus.QUEUED && steps.stream().anyMatch(step -> step.status() == StepStatus.WAITING)
                && turn.getReply().isBlank()) {
            turn.reply("下一步需要确认后再执行。");
        }
        if (next == RunStatus.FAILED && turn.getError() == null) {
            turn.error(failureOf(steps));
        }
        turn.update(write(steps), next);
    }

    private List<PlanStep> dispatchReady(AgentTurn turn, List<PlanStep> current) {
        List<PlanStep> steps = new ArrayList<>(current);
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (PlanStep step : PlanRules.ready(steps)) {
                Optional<ToolSpec<?>> spec = registry.find(step.tool());
                if (spec.isEmpty()) {
                    replace(steps, step.withStatus(StepStatus.FAILED));
                    turn.error("未注册的工具：" + step.tool());
                    continue;
                }
                if (spec.get().needsApproval() && !step.approved()) {
                    replace(steps, step.withStatus(StepStatus.WAITING));
                    continue;
                }
                int attempt = step.attempt() + 1;
                ToolRun run;
                try {
                    run = tools.submit(turn.getUserId(), step.tool(), step.params(), turn.getSessionId(),
                            new StepRef(turn.getId(), step.id(), attempt));
                } catch (ApiException rejected) {
                    // 规划时合法、执行时不再合法（例如画布已变）：记为这一步失败，可单步重试
                    replace(steps, step.dispatched(null, StepStatus.FAILED, attempt));
                    turn.error(describeFailure(step, rejected.getMessage()));
                    continue;
                } catch (DataIntegrityViolationException duplicate) {
                    // 这一步的这一次派发已经存在（上次中断留下的），以库里的任务为准
                    log.warn("步骤已派发过，按库里任务对账 turn_id={} step_id={} attempt={}", turn.getId(), step.id(),
                            attempt);
                    steps = new ArrayList<>(reconcile(turn, steps));
                    progressed = true;
                    break;
                }
                replace(steps, step.dispatched(run.getId(), StepStatus.of(run.getStatus()), attempt));
                progressed |= run.getStatus() == RunStatus.SUCCEEDED;
            }
        }
        return steps;
    }

    /**
     * 以 tool_runs 为准刷新步骤：取该步派发次数不低于计划所记的最新任务。
     * 单步重试会清掉 run_id，同一次派发的旧任务就不会被认回来。
     */
    private List<PlanStep> reconcile(AgentTurn turn, List<PlanStep> steps) {
        List<ToolRun> bound = runs.findByTurnIdOrderByCreatedAtAsc(turn.getId());
        if (bound.isEmpty()) {
            return steps;
        }
        List<PlanStep> synced = new ArrayList<>(steps.size());
        for (PlanStep step : steps) {
            Optional<ToolRun> latest = bound.stream()
                    .filter(run -> step.id().equals(run.getStepId()) && run.getStepAttempt() != null)
                    .filter(run -> run.getStepAttempt() > step.attempt()
                            || (run.getStepAttempt() == step.attempt() && run.getId().equals(step.runId())))
                    .max(Comparator.comparingInt(ToolRun::getStepAttempt));
            synced.add(latest.map(run -> step.dispatched(run.getId(), StepStatus.of(run.getStatus()),
                    run.getStepAttempt())).orElse(step));
        }
        return synced;
    }

    private String failureOf(List<PlanStep> steps) {
        for (PlanStep step : steps) {
            if (step.status() == StepStatus.FAILED) {
                String reason = step.runId() == null ? null
                        : runs.findById(step.runId()).map(ToolRun::getError).orElse(null);
                return describeFailure(step, reason == null ? "执行失败" : reason);
            }
        }
        return null;
    }

    private static String describeFailure(PlanStep step, String reason) {
        return "「" + step.label() + "」失败：" + reason;
    }

    private AgentTurn find(UUID sessionId, UUID turnId) {
        return turns.findByIdAndSessionId(turnId, sessionId).orElseThrow(() -> ApiException.notFound("对话不存在"));
    }

    private static void replace(List<PlanStep> steps, PlanStep next) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(next.id())) {
                steps.set(i, next);
                return;
            }
        }
    }

    private List<PlanStep> read(AgentTurn turn) {
        return json.readValue(turn.getPlan(), new TypeReference<List<PlanStep>>() {
        });
    }

    private String write(List<PlanStep> steps) {
        return json.writeValueAsString(steps);
    }

    // ---- 答复 ----

    /** 有计划：模型给了文字就用，否则按步骤生成；没计划：取模型答复的第一句，空则给兜底话术。 */
    static String spoken(String text, List<PlanStep> plan) {
        String reply = text == null ? "" : text.strip();
        if (!plan.isEmpty()) {
            if (!reply.isEmpty() && !reply.equals(FALLBACK_REPLY)) {
                return reply;
            }
            String labels = String.join("、", plan.stream().map(PlanStep::label).toList());
            return plan.size() > 1 ? "将按以下步骤执行：" + labels + "。确认后开始。" : "好，正在" + labels + "。";
        }
        return reply.isEmpty() ? FALLBACK_REPLY : firstSentence(reply);
    }

    static String firstSentence(String text) {
        int cut = -1;
        for (char sep : new char[] {'。', '！', '？', '\n'}) {
            int at = text.indexOf(sep);
            if (at > 0 && !text.substring(0, at).isBlank() && (cut < 0 || at < cut)) {
                cut = at;
            }
        }
        if (cut > 0) {
            char sep = text.charAt(cut);
            return text.substring(0, cut).strip() + (sep == '\n' ? '。' : sep);
        }
        if (text.codePointCount(0, text.length()) > REPLY_LIMIT) {
            return text.substring(0, text.offsetByCodePoints(0, REPLY_LIMIT)).strip() + "…";
        }
        return text;
    }
}
