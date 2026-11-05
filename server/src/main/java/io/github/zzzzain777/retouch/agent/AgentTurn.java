package io.github.zzzzain777.retouch.agent;

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

import io.github.zzzzain777.retouch.tool.RunStatus;

/**
 * 一轮自然语言指令。plan 就是断点：每一步的状态、任务 id 与派发次数都在里面，重启后从这里接着推进。
 * 改它之前一律先锁行（{@link AgentTurnRepository#lockById}）。
 */
@Entity
@Table(name = "agent_turns")
public class AgentTurn {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** 规划时会话的修订号；确认时与当前修订号比对，画布已变就拒绝执行旧计划。 */
    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private String goal;

    @Column(nullable = false)
    private String reply;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String plan;

    @Convert(converter = RunStatus.Column.class)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    private String error;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String trace;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentTurn() {
    }

    AgentTurn(UUID userId, UUID sessionId, int revision, String goal, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.sessionId = sessionId;
        this.revision = revision;
        this.goal = goal;
        this.reply = "";
        this.plan = "[]";
        this.trace = "{}";
        this.status = RunStatus.QUEUED;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String planJson, RunStatus next) {
        this.plan = planJson;
        this.status = next;
        this.updatedAt = Instant.now();
    }

    void reply(String text) {
        this.reply = text == null ? "" : text;
    }

    void error(String message) {
        this.error = message;
    }

    void trace(String json) {
        this.trace = json;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getSessionId() { return sessionId; }
    public int getRevision() { return revision; }
    public String getGoal() { return goal; }
    public String getReply() { return reply; }
    public String getPlan() { return plan; }
    public RunStatus getStatus() { return status; }
    public String getError() { return error; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getTrace() { return trace; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
