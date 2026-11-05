package io.github.zzzzain777.retouch.tool;

/** 任务进入终态后的回调（在完成事务提交之后调用）。Agent 用它推进计划的下一步。 */
public interface RunListener {
    void finished(ToolRun run);
}
