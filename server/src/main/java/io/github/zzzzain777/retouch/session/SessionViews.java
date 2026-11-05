package io.github.zzzzain777.retouch.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import io.github.zzzzain777.retouch.asset.Asset;
import io.github.zzzzain777.retouch.asset.AssetOut;
import io.github.zzzzain777.retouch.asset.AssetRepository;
import io.github.zzzzain777.retouch.asset.AssetService;

/** 会话相关的响应体，字段与上游契约一致。 */
@Component
public class SessionViews {

    public record SessionOut(UUID id, String title, int revision, int historySeq, UUID originalAssetId,
            UUID currentAssetId, Instant createdAt, Instant updatedAt) {
    }

    public record SessionDetailOut(UUID id, String title, int revision, int historySeq, UUID originalAssetId,
            UUID currentAssetId, Instant createdAt, Instant updatedAt, LayerDocument document,
            LayerDocument previousDocument, List<AssetOut> assets, boolean canUndo, boolean canRedo) {
    }

    public record HistoryOut(int seq, String action, JsonNode params, JsonNode result, Instant createdAt) {
    }

    private final SessionService sessions;
    private final AssetService assets;
    private final AssetRepository assetRows;

    SessionViews(SessionService sessions, AssetService assets, AssetRepository assetRows) {
        this.sessions = sessions;
        this.assets = assets;
        this.assetRows = assetRows;
    }

    public SessionOut summary(EditSession s) {
        return new SessionOut(s.getId(), s.getTitle(), s.getRevision(), s.getHistorySeq(), s.getOriginalAssetId(),
                s.getCurrentAssetId(), s.getCreatedAt(), s.getUpdatedAt());
    }

    public SessionDetailOut detail(EditSession s) {
        List<UUID> ids = sessions.wallAssetIds(s);
        var byId = new HashMap<UUID, Asset>();
        assetRows.findByIdInAndUserId(ids, s.getUserId()).forEach(asset -> byId.put(asset.getId(), asset));
        List<AssetOut> wall = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).map(assets::out)
                .toList();
        return new SessionDetailOut(s.getId(), s.getTitle(), s.getRevision(), s.getHistorySeq(),
                s.getOriginalAssetId(), s.getCurrentAssetId(), s.getCreatedAt(), s.getUpdatedAt(),
                sessions.document(s), sessions.previousDocument(s), wall, sessions.canUndo(s), sessions.canRedo(s));
    }

    public HistoryOut history(EditHistory entry) {
        return new HistoryOut(entry.getSeq(), entry.getAction(), sessions.read(entry.getParams()),
                sessions.read(entry.getResult()), entry.getCreatedAt());
    }
}
