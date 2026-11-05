package io.github.zzzzain777.retouch.asset;

import java.time.Instant;
import java.util.UUID;

/** 与上游 AssetOut 字段一致；url 为每次读取时重新签发的短时签名地址。 */
public record AssetOut(UUID id, AssetKind kind, AssetSource source, String imageFormat, int width, int height,
        long sizeBytes, boolean hasAlpha, Instant createdAt, String url) {
}
