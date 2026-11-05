package io.github.zzzzain777.retouch.asset;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.zzzzain777.retouch.common.LowercaseEnumConverter;

@Entity
@Table(name = "assets")
public class Asset {

    @Converter
    static class KindConverter extends LowercaseEnumConverter<AssetKind> {
        KindConverter() {
            super(AssetKind.class);
        }
    }

    @Converter
    static class SourceConverter extends LowercaseEnumConverter<AssetSource> {
        SourceConverter() {
            super(AssetSource.class);
        }
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Convert(converter = KindConverter.class)
    @Column(nullable = false)
    private AssetKind kind;

    @Convert(converter = SourceConverter.class)
    @Column(nullable = false)
    private AssetSource source;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "image_format", nullable = false)
    private String imageFormat;

    private int width;

    private int height;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "has_alpha")
    private boolean hasAlpha;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Asset() {
    }

    public Asset(UUID id, UUID userId, AssetKind kind, AssetSource source, String storageKey, ImageMeta meta,
            String sha256) {
        this.id = id;
        this.userId = userId;
        this.kind = kind;
        this.source = source;
        this.storageKey = storageKey;
        this.imageFormat = meta.format().name();
        this.width = meta.width();
        this.height = meta.height();
        this.sizeBytes = meta.sizeBytes();
        this.hasAlpha = meta.hasAlpha();
        this.sha256 = sha256;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public AssetKind getKind() { return kind; }
    public AssetSource getSource() { return source; }
    public String getStorageKey() { return storageKey; }
    public String getImageFormat() { return imageFormat; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getSizeBytes() { return sizeBytes; }
    public boolean isHasAlpha() { return hasAlpha; }
    public String getSha256() { return sha256; }
    public Instant getCreatedAt() { return createdAt; }
}
