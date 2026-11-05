package io.github.zzzzain777.retouch.agent;

import com.fasterxml.jackson.annotation.JsonValue;

import io.github.zzzzain777.retouch.tool.RunStatus;

/** 计划步骤的状态。派发后镜像对应任务（ToolRun）的状态。 */
public enum StepStatus {
    /** 未开始。 */
    PENDING,
    /** 轮到它了，但这个工具需要用户再确认一次。 */
    WAITING,
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED;

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }

    /** 还没派发（可能派发）的步骤。 */
    public boolean undispatched() {
        return this == PENDING || this == WAITING;
    }

    public boolean inFlight() {
        return this == QUEUED || this == RUNNING;
    }

    /** 失败或取消：依赖它的后续步骤不会再执行。 */
    public boolean blocking() {
        return this == FAILED || this == CANCELED;
    }

    public static StepStatus of(RunStatus status) {
        return valueOf(status.name());
    }
}
