package io.github.zzzzain777.retouch.knowledge;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合规检查的独立入口：只做「按目标检索规范 + 校验计划」，不参与主对话流程。
 *
 * <p>与 Agent 主流程解耦：不改 Planner / AgentService，现有测试与行为评测不受影响。
 */
@RestController
@RequestMapping("/api/compliance")
class ComplianceController {

    record CheckRequest(
            @NotBlank String platform,
            @NotBlank String category,
            /** 本轮目标，用户原话；检索按它召回适用条款。 */
            @NotBlank @Size(max = 500) String goal,
            @NotEmpty List<String> tools) {
    }

    record CheckResponse(boolean passed, String query, List<String> sources,
            List<ComplianceChecker.Finding> findings, List<String> violations) {
    }

    private final ComplianceChecker checker;

    ComplianceController(ComplianceChecker checker) {
        this.checker = checker;
    }

    @PostMapping("/check")
    CheckResponse check(@Valid @RequestBody CheckRequest request) {
        ComplianceChecker.Result result =
                checker.check(request.platform(), request.category(), request.goal(), request.tools());
        return new CheckResponse(result.passed(), result.query(), result.sources(),
                result.findings(), result.violations());
    }
}
