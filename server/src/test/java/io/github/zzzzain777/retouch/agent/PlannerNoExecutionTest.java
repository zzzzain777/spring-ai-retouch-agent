package io.github.zzzzain777.retouch.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import io.github.zzzzain777.retouch.tool.ToolRegistry;
import io.github.zzzzain777.retouch.tool.ToolSchemas;

/**
 * 规划阶段不执行工具：模型返回工具调用时，Spring AI 不得替我们执行，提议原样交回给 Java。
 * 用脚本模型代替真实模型，只验证调用链路，不涉及模型规划质量。
 */
class PlannerNoExecutionTest {

    private final ToolRegistry registry = new ToolRegistry();
    private final ScriptedChatModel model = new ScriptedChatModel();

    private SpringAiPlanner planner(String apiKey) {
        return new SpringAiPlanner(model, registry, new ToolSchemas(registry), new PlannerPrompt(registry), apiKey,
                "deepseek-chat");
    }

    @Test
    void returnsToolCallsAsProposalsWithoutExecutingThem() {
        model.thenCalls(Map.entry("flip_layer", Map.of("direction", "horizontal")),
                Map.entry("adjust_image", Map.of("brightness", 0.2)));

        var proposal = planner("test-key").plan("先水平翻转再调亮", "画布 100×100");

        assertThat(proposal.calls()).extracting(Planner.Call::tool).containsExactly("flip_layer", "adjust_image");
        assertThat(proposal.calls().get(0).arguments()).contains("horizontal");
        assertThat(model.received()).hasSize(1);
        assertThat(proposal.trace().promptTokens()).isEqualTo(100);
        assertThat(proposal.trace().toolsOffered()).isEqualTo(registry.all().size());
    }

    @Test
    void offersEveryRegisteredToolWithItsSchemaAndTheCanvasSummary() {
        model.thenText("好的");
        planner("test-key").plan("调亮", "画布 640×480；修订号 3");

        var prompt = model.received().getFirst();
        var options = (ToolCallingChatOptions) prompt.getOptions();
        assertThat(options.getToolCallbacks()).extracting(cb -> cb.getToolDefinition().name())
                .containsExactlyElementsOf(registry.all().stream().map(spec -> spec.name()).toList());
        assertThat(options.getToolCallbacks().getFirst().getToolDefinition().inputSchema()).contains("\"type\":\"object\"");
        assertThat(prompt.getSystemMessage().getText()).endsWith("当前画布：画布 640×480；修订号 3");
        assertThat(prompt.getUserMessage().getText()).isEqualTo("调亮");
    }

    @Test
    void toolCallbacksRefuseToRun() {
        ToolCallback tool = new SpringAiPlanner.PlanningOnlyTool(
                DefaultToolDefinition.builder().name("flip_layer").description("d").inputSchema("{}").build());
        assertThatThrownBy(() -> tool.call("{}")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("规划阶段禁止执行工具");
    }

    /** 对照组：同样的工具与模型输出，若不关掉 ToolCallingAdvisor，Spring AI 就会去执行工具（被我们的回调拦下）。 */
    @Test
    void withoutTheGuardSpringAiWouldExecuteTools() {
        model.thenCalls(Map.entry("flip_layer", Map.of("direction", "horizontal"))).thenText("完成");
        ToolCallback tool = new SpringAiPlanner.PlanningOnlyTool(
                DefaultToolDefinition.builder().name("flip_layer").description("d").inputSchema("{}").build());
        var unguarded = ChatClient.builder(model).build();

        assertThatThrownBy(() -> unguarded.prompt().user("翻转").toolCallbacks(List.of(tool)).call().chatResponse())
                .hasStackTraceContaining("规划阶段禁止执行工具");
    }

    @Test
    void missingKeyMeansPlannerUnavailable() {
        assertThatThrownBy(() -> planner(" ").plan("调亮", "画布"))
                .isInstanceOf(Planner.Unavailable.class).hasMessage("未配置 DEEPSEEK_API_KEY，对话指令不可用");
        assertThat(model.received()).isEmpty();
    }
}
