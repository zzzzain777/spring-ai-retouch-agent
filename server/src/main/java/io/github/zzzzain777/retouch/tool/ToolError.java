package io.github.zzzzain777.retouch.tool;

/** 可以直接展示给用户的工具失败：任务记为 failed，错误写进 run.error，不是 500。 */
public class ToolError extends RuntimeException {
    public ToolError(String message) {
        super(message);
    }
}
