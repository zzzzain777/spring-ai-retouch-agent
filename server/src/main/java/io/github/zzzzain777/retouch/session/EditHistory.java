package io.github.zzzzain777.retouch.session;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 线性编辑记录。params.before 存编辑前快照，result 存编辑后快照；撤销与重做只在这两份快照之间移动 history_seq。
 */
@Entity
@Table(name = "edit_history")
public class EditHistory {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 48)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String params;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String result;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EditHistory() {
    }

    EditHistory(UUID userId, UUID sessionId, int seq, String action, String params, String result) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.sessionId = sessionId;
        this.seq = seq;
        this.action = action;
        this.params = params;
        this.result = result;
        this.createdAt = Instant.now();
    }

    public int getSeq() { return seq; }
    public String getAction() { return action; }
    public String getParams() { return params; }
    public String getResult() { return result; }
    public Instant getCreatedAt() { return createdAt; }
}
