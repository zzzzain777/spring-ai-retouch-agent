package io.github.zzzzain777.retouch.asset;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.storage.ObjectStorage;

/** 素材的唯一入口：所有读取都带所有者过滤，所有写入都先校验图片、再写对象存储、最后落库。 */
@Service
public class AssetService {

    private final AssetRepository assets;
    private final ObjectStorage storage;
    private final ImageProbe probe;

    public AssetService(AssetRepository assets, ObjectStorage storage, ImageProbe probe) {
        this.assets = assets;
        this.storage = storage;
        this.probe = probe;
    }

    @Transactional
    public Asset createFromBytes(UUID userId, byte[] data, AssetKind kind, AssetSource source) {
        ImageMeta meta = probe.probe(data);
        UUID id = UUID.randomUUID();
        String key = "users/%s/%s.%s".formatted(userId, id, meta.format().extension());
        storage.put(key, data, meta.format().contentType());
        return assets.save(new Asset(id, userId, kind, source, key, meta, sha256(data)));
    }

    /**
     * 登记图像服务已经写入对象存储的产物。键必须在本次任务的输出前缀下，防止被诱导引用他人的对象。
     */
    @Transactional
    public Asset register(UUID userId, String key, String requiredPrefix, ImageMeta meta, String sha256,
            AssetKind kind, AssetSource source) {
        if (!key.startsWith(requiredPrefix)) {
            throw new IllegalStateException("图像服务返回的对象键不在任务输出前缀下：" + key);
        }
        return assets.save(new Asset(UUID.randomUUID(), userId, kind, source, key, meta, sha256));
    }

    public Optional<Asset> find(UUID userId, UUID assetId) {
        return assets.findByIdAndUserId(assetId, userId);
    }

    public Asset require(UUID userId, UUID assetId, String detail) {
        return find(userId, assetId).orElseThrow(() -> ApiException.notFound(detail));
    }

    public List<Asset> recent(UUID userId, int limit) {
        return assets.findByUserIdOrderByCreatedAtDesc(userId, Limit.of(limit));
    }

    public byte[] bytes(Asset asset) {
        return storage.get(asset.getStorageKey());
    }

    public AssetOut out(Asset asset) {
        return new AssetOut(asset.getId(), asset.getKind(), asset.getSource(), asset.getImageFormat(),
                asset.getWidth(), asset.getHeight(), asset.getSizeBytes(), asset.isHasAlpha(), asset.getCreatedAt(),
                storage.presignedUrl(asset.getStorageKey()).toString());
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException(exc);
        }
    }
}
