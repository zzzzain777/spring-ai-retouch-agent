package io.github.zzzzain777.retouch.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 测试专用：按脚本返回固定的模型输出（文字或工具调用），并记下收到的请求。
 * 只用来验证 Java 侧的计划校验、确认门与执行流程，**不代表任何真实模型的规划能力**，评测结论只能来自真实模型。
 */
public class ScriptedChatModel implements ChatModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Deque<Supplier<AssistantMessage>> script = new ArrayDeque<>();
    private final List<Prompt> received = new ArrayList<>();

    /** 下一次调用返回这些工具调用（按顺序）。每个调用是 工具名 → 参数。 */
    @SafeVarargs
    public final ScriptedChatModel thenCalls(Map.Entry<String, Map<String, Object>>... calls) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < calls.length; i++) {
            toolCalls.add(new AssistantMessage.ToolCall("call_" + i, "function", calls[i].getKey(),
                    JSON.writeValueAsString(calls[i].getValue())));
        }
        script.add(() -> AssistantMessage.builder().content("").toolCalls(toolCalls).build());
        return this;
    }

    /** 下一次调用返回原样的参数文本（用于构造非法 JSON）。 */
    public ScriptedChatModel thenRawCall(String tool, String arguments) {
        script.add(() -> AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_0", "function", tool, arguments))).build());
        return this;
    }

    public ScriptedChatModel thenText(String text) {
        script.add(() -> new AssistantMessage(text));
        return this;
    }

    public ScriptedChatModel thenFail(RuntimeException failure) {
        script.add(() -> {
            throw failure;
        });
        return this;
    }

    public synchronized List<Prompt> received() {
        return List.copyOf(received);
    }

    public synchronized void reset() {
        script.clear();
        received.clear();
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        received.add(prompt);
        Supplier<AssistantMessage> next = script.poll();
        if (next == null) {
            throw new IllegalStateException("脚本里没有更多的模型输出");
        }
        var metadata = ChatResponseMetadata.builder().model("scripted").usage(new DefaultUsage(100, 10)).build();
        return new ChatResponse(List.of(new Generation(next.get())), metadata);
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }
}
