package io.github.zzzzain777.retouch.tool;

import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

import io.github.zzzzain777.retouch.session.LayerDocument;

/**
 * 一个工具的完整定义，手动接口与 Agent 共用同一份。
 * 参数类型同时决定服务端校验规则和交给规划模型的 JSON Schema，两边不会漂移。
 *
 * @param agentHidden 由服务端从上下文填入、不暴露给模型的参数（如遮罩素材 id、随机种子）
 * @param idempotent  中断后能否安全地自动重排；调用远程生成模型的工具为 false
 */
public record ToolSpec<P>(String name, String label, String description, Class<P> paramsType, Execution execution,
        boolean sessionRequired, boolean usesMask, Set<String> agentHidden, Duration timeout, int maxAttempts,
        boolean idempotent, boolean needsApproval, Function<P, String> detail, DocumentHandler<P> documentHandler,
        ImageHandler<P> imageHandler) {

    public enum Execution {
        /** 只改画布文档，在请求内同步完成。 */
        DOCUMENT,
        /** 需要像素计算或图像模型，排队后由 Worker 调 Python 图像服务。 */
        IMAGE,
        /** 组合多个图像操作（批量处理）。 */
        COMPOSITE;

        @com.fasterxml.jackson.annotation.JsonValue
        public String value() {
            return name().toLowerCase();
        }
    }

    /** 文档工具：输入当前文档与参数，返回新文档；不能应用时抛 {@link ToolError}。 */
    @FunctionalInterface
    public interface DocumentHandler<P> {
        LayerDocument apply(LayerDocument document, P params);
    }

    /**
     * 图像工具：在 Worker 线程里执行，可以读文档、报告进度、调图像服务，最后返回要怎样改会话；
     * 改会话这一步由 Worker 在完成事务里统一做（锁会话行、登记素材、写历史）。
     */
    @FunctionalInterface
    public interface ImageHandler<P> {
        ImageOutcome run(ImageContext context, P params);
    }

    public static <P> ToolSpec<P> document(String name, String label, String description, Class<P> type,
            Set<String> hidden, Function<P, String> detail, DocumentHandler<P> handler) {
        return new ToolSpec<>(name, label, description, type, Execution.DOCUMENT, true, false, hidden,
                Duration.ofSeconds(10), 1, true, false, detail, handler, null);
    }

    public static <P> ToolSpec<P> image(String name, String label, String description, Class<P> type,
            boolean usesMask, Set<String> hidden, Duration timeout, boolean idempotent, ImageHandler<P> handler) {
        return new ToolSpec<>(name, label, description, type, Execution.IMAGE, true, usesMask, hidden, timeout,
                idempotent ? 2 : 1, idempotent, false, null, null, handler);
    }

    /** 不需要会话的工具（文生图、批量处理）：调远程模型，中断后不自动重排。 */
    public static <P> ToolSpec<P> sessionless(String name, String label, String description, Class<P> type,
            Execution execution, Set<String> hidden, Duration timeout, ImageHandler<P> handler) {
        return new ToolSpec<>(name, label, description, type, execution, false, false, hidden, timeout, 1, false,
                false, null, null, handler);
    }

    /** 同一工具按参数细分说法（例如翻转分水平与垂直），没有细分时用 label。 */
    public String labelFor(P params) {
        String refined = detail == null || params == null ? null : detail.apply(params);
        return refined == null ? label : refined;
    }
}
