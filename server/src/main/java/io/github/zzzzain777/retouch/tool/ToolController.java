package io.github.zzzzain777.retouch.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.session.SessionService;
import io.github.zzzzain777.retouch.session.SessionViews;
import io.github.zzzzain777.retouch.session.SessionViews.SessionDetailOut;
import io.github.zzzzain777.retouch.tool.RunViews.RunOut;

@RestController
class ToolController {

    record ToolInvokeIn(@NotBlank String tool, Map<String, Object> params) {
    }

    record ToolInvokeOut(RunOut run, SessionDetailOut session) {
    }

    /** 新增接口：前端按它生成工具面板，内容与 Agent 看到的工具规格同源。 */
    record ToolOut(String name, String label, String description, ToolSpec.Execution execution,
            boolean sessionRequired, boolean usesMask, boolean needsApproval, Set<String> agentHidden) {
    }

    private final ToolService tools;
    private final ToolRegistry registry;
    private final ToolRunRepository runs;
    private final RunViews runViews;
    private final SessionService sessions;
    private final SessionViews sessionViews;

    ToolController(ToolService tools, ToolRegistry registry, ToolRunRepository runs, RunViews runViews,
            SessionService sessions, SessionViews sessionViews) {
        this.tools = tools;
        this.registry = registry;
        this.runs = runs;
        this.runViews = runViews;
        this.sessions = sessions;
        this.sessionViews = sessionViews;
    }

    @PostMapping("/api/sessions/{sessionId}/tools")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ToolInvokeOut invoke(CurrentUser user, @PathVariable UUID sessionId, @Valid @RequestBody ToolInvokeIn in) {
        sessions.get(user.id(), sessionId);
        ToolRun run = tools.submit(user.id(), in.tool(), in.params(), sessionId, null);
        return new ToolInvokeOut(runViews.of(run), sessionViews.detail(sessions.get(user.id(), sessionId)));
    }

    @GetMapping("/api/runs/{runId}")
    RunOut run(CurrentUser user, @PathVariable UUID runId) {
        return runViews.withCandidates(
                runs.findByIdAndUserId(runId, user.id()).orElseThrow(() -> ApiException.notFound("任务不存在")));
    }

    @GetMapping("/api/tools")
    List<ToolOut> list() {
        return registry.all().stream().map(spec -> new ToolOut(spec.name(), spec.label(), spec.description(),
                spec.execution(), spec.sessionRequired(), spec.usesMask(), spec.needsApproval(), spec.agentHidden()))
                .toList();
    }
}
