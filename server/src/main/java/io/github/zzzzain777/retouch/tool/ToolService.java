package io.github.zzzzain777.retouch.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.session.EditSession;
import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerMissing;
import io.github.zzzzain777.retouch.session.SessionService;
import io.github.zzzzain777.retouch.session.SessionService.Edit;

/**
 * 手动接口与 Agent 计划执行的唯一提交入口：同一套参数校验、同一套资源授权、同一套执行外壳。
 * 文档工具在请求内执行；图像工具只建任务排队，由 Worker 执行。
 */
@Service
public class ToolService {

    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    /** 计划步骤派发时带上的来源，手动调用为 null。 */
    public record StepRef(UUID turnId, String stepId, int attempt) {
    }

    /** 计划校验的结果：规范化后的参数与界面文案。 */
    public record Checked(String tool, Map<String, Object> params, String label) {
    }

    private final ToolRegistry registry;
    private final ToolParams params;
    private final ToolRunRepository runs;
    private final SessionService sessions;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final RunEvents events;

    public ToolService(ToolRegistry registry, ToolParams params, ToolRunRepository runs, SessionService sessions,
            PlatformTransactionManager transactions, JsonMapper json, RunEvents events) {
        this.events = events;
        this.registry = registry;
        this.params = params;
        this.runs = runs;
        this.sessions = sessions;
        // 总是开独立事务：Agent 推进计划时锁着轮次行调用这里，任务与编辑要各自提交、各自回滚，
        // 文档工具失败也不能把外层的计划更新一起标成只能回滚
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
    }

    public ToolSpec<?> spec(String name) {
        return registry.find(name).orElseThrow(() -> ApiException.notFound("未注册的工具：" + name));
    }

    /** 只校验不提交，Agent 规划后校验计划时用。返回规范化后的参数。 */
    public Map<String, Object> validate(String tool, Map<String, Object> raw) {
        return params.parse(spec(tool), raw).json();
    }

    /** 与 {@link #validate} 相同，另给出这一步的界面文案（例如“水平翻转”）。 */
    public Checked check(String tool, Map<String, Object> raw) {
        return checkTyped(spec(tool), raw);
    }

    private <P> Checked checkTyped(ToolSpec<P> spec, Map<String, Object> raw) {
        var parsed = params.parse(spec, raw);
        return new Checked(spec.name(), parsed.json(), spec.labelFor(parsed.value()));
    }

    /** 调用方须已确认 sessionId 属于 userId。 */
    public ToolRun submit(UUID userId, String tool, Map<String, Object> raw, UUID sessionId, StepRef step) {
        return submitTyped(spec(tool), userId, raw, sessionId, step);
    }

    private <P> ToolRun submitTyped(ToolSpec<P> spec, UUID userId, Map<String, Object> raw, UUID sessionId,
            StepRef step) {
        if (spec.sessionRequired() && sessionId == null) {
            throw ApiException.unprocessable("此工具需要在编辑会话中使用");
        }
        var parsed = params.parse(spec, raw);
        ToolRun created = tx.execute(status -> {
            var run = new ToolRun(userId, sessionId, spec.name(), params.write(parsed.json()), spec.maxAttempts());
            if (step != null) {
                run.bindStep(step.turnId(), step.stepId(), step.attempt());
            }
            return runs.saveAndFlush(run);
        });
        if (spec.execution() == ToolSpec.Execution.DOCUMENT) {
            executeDocument(spec, created.getId(), parsed);
            ToolRun done = runs.findById(created.getId()).orElseThrow();
            events.publish(done);
            return done;
        }
        events.publish(created);
        return created;
    }

    /**
     * 请求取消：排队中的任务直接取消；运行中的只做标记，Worker 在下一个检查点结束并丢弃远程结果
     * （远程图像模型调用本身无法中途取消）。返回取消后的任务。
     */
    public ToolRun requestCancel(UUID runId) {
        ToolRun run = tx.execute(status -> {
            ToolRun locked = runs.lockById(runId).orElseThrow();
            if (locked.getStatus() == RunStatus.QUEUED) {
                locked.cancel();
            } else if (locked.getStatus() == RunStatus.RUNNING) {
                locked.requestCancel();
            }
            return locked;
        });
        events.publish(run);
        return run;
    }

    /**
     * 文档工具的执行外壳：锁会话行 → 计算新文档 → 写编辑与历史 → 任务成功，在一个事务里完成。
     * 工具自身的失败（图层不存在、裁剪过小）记为任务 failed；意外异常回滚编辑，任务记为“执行失败，请重试”。
     */
    private <P> void executeDocument(ToolSpec<P> spec, UUID runId, ToolParams.Parsed<P> parsed) {
        try {
            tx.executeWithoutResult(status -> {
                ToolRun run = runs.findById(runId).orElseThrow();
                run.start();
                EditSession record = sessions.lock(run.getUserId(), run.getSessionId());
                LayerDocument next = spec.documentHandler().apply(sessions.document(record), parsed.value());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("document", json.convertValue(next, Map.class));
                sessions.apply(record, spec.name(), new Edit(parsed.json(), next, null, List.of(), result, true));
                run.succeed(json.writeValueAsString(result));
            });
        } catch (ToolError | LayerMissing failure) {
            finishFailed(runId, failure.getMessage());
        } catch (ApiException failure) {
            finishFailed(runId, failure.getMessage());
        } catch (RuntimeException unexpected) {
            log.error("工具执行异常 tool={} run_id={}", spec.name(), runId, unexpected);
            finishFailed(runId, "执行失败，请重试");
        }
    }

    private void finishFailed(UUID runId, String message) {
        tx.executeWithoutResult(status -> runs.findById(runId).orElseThrow().fail(message));
    }
}
