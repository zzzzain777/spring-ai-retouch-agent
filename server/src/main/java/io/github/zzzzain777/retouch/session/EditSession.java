package io.github.zzzzain777.retouch.session;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 编辑会话：画布文档、修订号与撤销位置的唯一权威。修订号只由 Java 改动。 */
@Entity
@Table(name = "edit_sessions")
public class EditSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(name = "original_asset_id", nullable = false)
    private UUID originalAssetId;

    @Column(name = "current_asset_id", nullable = false)
    private UUID currentAssetId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "history_seq", nullable = false)
    private int historySeq;

    /** 画布文档的 JSON 文本（jsonb 列）；序列化由 SessionService 用应用统一的 JsonMapper 完成。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String document;

    /** 乐观锁：两个请求同时改同一会话时，后提交的一方失败并返回 409，而不是悄悄覆盖。 */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EditSession() {
    }

    EditSession(UUID id, UUID userId, String title, UUID assetId, String document) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.originalAssetId = assetId;
        this.currentAssetId = assetId;
        this.document = document;
        this.revision = 1;
        this.historySeq = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public UUID getOriginalAssetId() { return originalAssetId; }
    public UUID getCurrentAssetId() { return currentAssetId; }
    public int getRevision() { return revision; }
    public int getHistorySeq() { return historySeq; }
    public String getDocument() { return document; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    void setTitle(String title) { this.title = title; }
    void setCurrentAssetId(UUID currentAssetId) { this.currentAssetId = currentAssetId; }
    void setRevision(int revision) { this.revision = revision; }
    void setHistorySeq(int historySeq) { this.historySeq = historySeq; }
    void setDocument(String document) { this.document = document; }
}
