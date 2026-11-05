package io.github.zzzzain777.retouch.agent;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.zzzzain777.retouch.tool.ToolRegistry;
import io.github.zzzzain777.retouch.tool.ToolSchemas;
import io.github.zzzzain777.retouch.tool.ToolSpec;

/**
 * 用 Spring AI ChatClient 调规划模型（OpenAI 兼容接口，默认 DeepSeek），拿到工具调用作为提议。
 * <p>
 * 规划阶段不执行工具，两道保险：
 * <ol>
 * <li>Spring AI 2.0 里工具的自动执行由 ChatClient 自动加上的 ToolCallingAdvisor 负责，这里每次调用都关掉它的自动注册，
 * 模型返回的 tool_calls 原样交回给我们；</li>
 * <li>交给模型的工具只有定义，没有实现：{@link PlanningOnlyTool#call} 一旦被调用就抛异常。</li>
 * </ol>
 * 有测试守住这两点（见 PlannerNoExecutionTest）。
 */
@Component
class SpringAiPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(SpringAiPlanner.class);

    private final ChatClient chat;
    private final List<ToolCallback> tools;
    private final PlannerPrompt prompt;
    private final String apiKey;
    private final String model;

    SpringAiPlanner(ChatModel chatModel, ToolRegistry registry, ToolSchemas schemas, PlannerPrompt prompt,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.model:}") String model) {
        this.chat = ChatClient.builder(chatModel).build();
        this.tools = registry.all().stream().<ToolCallback>map(spec -> new PlanningOnlyTool(definition(spec, schemas)))
                .toList();
        this.prompt = prompt;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    @Override
    public Proposal plan(String goal, String canvas) {
        if (apiKey.isEmpty()) {
            throw new Unavailable("未配置 DEEPSEEK_API_KEY，对话指令不可用");
        }
        long started = System.nanoTime();
        ChatResponse response = chat.prompt()
                .system(prompt.render(canvas))
                .user(goal)
                .toolCallbacks(tools)
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .call()
                .chatResponse();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        Generation generation = response == null ? null : response.getResult();
        AssistantMessage message = generation == null ? null : generation.getOutput();
        String text = message == null || message.getText() == null ? "" : message.getText().strip();
        List<Call> calls = message == null ? List.of()
                : message.getToolCalls().stream().map(call -> new Call(call.name(), call.arguments())).toList();
        Usage usage = response == null ? null : response.getMetadata().getUsage();
        String servedModel = response == null || response.getMetadata().getModel() == null
                || response.getMetadata().getModel().isBlank() ? model : response.getMetadata().getModel();
        String finish = generation == null ? null : generation.getMetadata().getFinishReason();
        var trace = new PlannerTrace(servedModel, elapsed, usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(), finish, tools.size(), calls,
                text.length() > 500 ? text.substring(0, 500) : text, null, null);
        log.info("规划完成 model={} elapsed_ms={} prompt_tokens={} completion_tokens={} tool_calls={}", servedModel,
                elapsed, trace.promptTokens(), trace.completionTokens(), calls.stream().map(Call::tool).toList());
        return new Proposal(text, calls, trace);
    }

    String model() {
        return model;
    }

    private static ToolDefinition definition(ToolSpec<?> spec, ToolSchemas schemas) {
        return DefaultToolDefinition.builder().name(spec.name()).description(spec.description())
                .inputSchema(schemas.schema(spec.name())).build();
    }

    /** 只有定义、没有实现的工具：规划阶段被调用说明自动执行没关掉，直接报错。 */
    static final class PlanningOnlyTool implements ToolCallback {

        private final ToolDefinition definition;

        PlanningOnlyTool(ToolDefinition definition) {
            this.definition = definition;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("规划阶段禁止执行工具：" + definition.name());
        }
    }
}
