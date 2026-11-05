package io.github.zzzzain777.retouch.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
 * <p>
 * 部分模型习惯一次只给一个工具调用、等结果再给下一个。{@code retouch.planner.follow-up-rounds} 大于 0 时，
 * 拿到工具调用后会回一条“已记录、未执行”的工具结果让模型补全剩余步骤，最多追问这么多轮；工具始终没有被执行。
 * 默认 0（只问一轮），是否打开以真实模型检查的结果为准。
 */
@Component
class SpringAiPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(SpringAiPlanner.class);

    private final ChatClient chat;
    private final List<ToolCallback> tools;
    private final PlannerPrompt prompt;
    static final String RECORDED = "已记录，尚未执行。指令里如果还有没给出的步骤，继续调用对应工具；已经全部给出就只回复“好”。";

    private final String apiKey;
    private final String model;
    private final int followUpRounds;

    SpringAiPlanner(ChatModel chatModel, ToolRegistry registry, ToolSchemas schemas, PlannerPrompt prompt,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.model:}") String model,
            @Value("${retouch.planner.follow-up-rounds:0}") int followUpRounds) {
        this.chat = ChatClient.builder(chatModel).build();
        this.tools = registry.all().stream().<ToolCallback>map(spec -> new PlanningOnlyTool(definition(spec, schemas)))
                .toList();
        this.prompt = prompt;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.followUpRounds = Math.max(0, Math.min(followUpRounds, PlanRules.MAX_STEPS - 1));
    }

    @Override
    public Proposal plan(String goal, String canvas) {
        if (apiKey.isEmpty()) {
            throw new Unavailable("未配置 DEEPSEEK_API_KEY，对话指令不可用");
        }
        long started = System.nanoTime();
        List<Message> history = new ArrayList<>(List.of(new SystemMessage(prompt.render(canvas)), new UserMessage(goal)));
        List<Call> calls = new ArrayList<>();
        List<Call> previous = List.of();
        String text = null;
        String servedModel = model;
        String finish = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        int rounds = 0;
        while (true) {
            ChatResponse response = chat.prompt()
                    .messages(history)
                    .toolCallbacks(tools)
                    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                    .call()
                    .chatResponse();
            rounds++;
            Generation generation = response == null ? null : response.getResult();
            AssistantMessage message = generation == null ? null : generation.getOutput();
            List<Call> round = message == null ? List.of()
                    : message.getToolCalls().stream().map(call -> new Call(call.name(), call.arguments())).toList();
            if (text == null) {
                text = message == null || message.getText() == null ? "" : message.getText().strip();
            }
            if (response != null) {
                Usage usage = response.getMetadata().getUsage();
                if (usage != null) {
                    promptTokens = sum(promptTokens, usage.getPromptTokens());
                    completionTokens = sum(completionTokens, usage.getCompletionTokens());
                }
                String served = response.getMetadata().getModel();
                servedModel = served == null || served.isBlank() ? servedModel : served;
            }
            if (generation != null && rounds == 1) {
                finish = generation.getMetadata().getFinishReason();
            }
            // 追问时模型把上一轮原样再给一遍，说明它没有更多步骤，不重复计入
            if (rounds > 1 && round.equals(previous)) {
                break;
            }
            calls.addAll(round);
            if (round.isEmpty() || rounds > followUpRounds || calls.size() >= PlanRules.MAX_STEPS) {
                break;
            }
            history.add(message);
            history.add(ToolResponseMessage.builder().responses(message.getToolCalls().stream()
                    .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(), RECORDED)).toList())
                    .build());
            previous = round;
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        var trace = new PlannerTrace(servedModel, elapsed, promptTokens, completionTokens, finish, tools.size(), rounds,
                List.copyOf(calls), text.length() > 500 ? text.substring(0, 500) : text, null, null);
        log.info("规划完成 model={} elapsed_ms={} rounds={} prompt_tokens={} completion_tokens={} tool_calls={}",
                servedModel, elapsed, rounds, promptTokens, completionTokens, calls.stream().map(Call::tool).toList());
        return new Proposal(text, List.copyOf(calls), trace);
    }

    private static Integer sum(Integer total, Integer more) {
        if (more == null) {
            return total;
        }
        return total == null ? more : total + more;
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
