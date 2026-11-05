package io.github.zzzzain777.retouch.tool;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一次工具调用。id 贯穿 SSE、Redis 频道、调用图像服务的 X-Run-Id 与对象存储输出前缀；排队工具的队列也就是这张表。
 */
@Entity
@Table(name = "tool_runs")
public class ToolRun {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "step_id", length = 16)
    private String stepId;

    @Column(name = "step_attempt")
    private Integer stepAttempt;

    @Column(nullable = false, length = 48)
    private String tool;

    @Convert(converter = RunStatus.Column.class)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    @Column(nullable = false)
    private int progress;

    @Column(nullable = false, length = 64)
    private String stage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String params;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String result;

    private String error;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ToolRun() {
    }

    ToolRun(UUID userId, UUID sessionId, String tool, String params, int maxAttempts) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.sessionId = sessionId;
        this.tool = tool;
        this.params = params;
        this.result = "{}";
        this.status = RunStatus.QUEUED;
        this.stage = "等待开始";
        this.maxAttempts = maxAttempts;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /** 计划步骤派发的任务记下轮次与步骤；同一步骤每次重试 stepAttempt 递增，唯一约束防止重复派发。 */
    void bindStep(UUID turnId, String stepId, int stepAttempt) {
        this.turnId = turnId;
        this.stepId = stepId;
        this.stepAttempt = stepAttempt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void start() {
        status = RunStatus.RUNNING;
        startedAt = Instant.now();
        attempt += 1;
        progress = Math.max(progress, 5);
        stage = "已开始";
    }

    void report(int value, String text) {
        progress = Math.max(progress, value);
        stage = text;
    }

    void succeed(String resultJson) {
        status = RunStatus.SUCCEEDED;
        result = resultJson;
        error = null;
        progress = 100;
        stage = "已完成";
        finish();
    }

    void fail(String message) {
        status = RunStatus.FAILED;
        result = "{}";
        error = message;
        stage = "已结束";
        finish();
    }

    /** 租约过期且工具幂等：放回队列，下一次执行 attempt 递增、输出写到新前缀。 */
    void requeue() {
        status = RunStatus.QUEUED;
        stage = "中断后重新排队";
        leaseUntil = null;
        workerId = null;
    }

    void requestCancel() {
        cancelRequested = true;
    }

    void cancel() {
        status = RunStatus.CANCELED;
        stage = "已结束";
        finish();
    }

    private void finish() {
        finishedAt = Instant.now();
        leaseUntil = null;
        workerId = null;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getSessionId() { return sessionId; }
    public UUID getTurnId() { return turnId; }
    public String getStepId() { return stepId; }
    public Integer getStepAttempt() { return stepAttempt; }
    public String getTool() { return tool; }
    public RunStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getStage() { return stage; }
    public String getParams() { return params; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public int getAttempt() { return attempt; }
    public int getMaxAttempts() { return maxAttempts; }
    public boolean isCancelRequested() { return cancelRequested; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
