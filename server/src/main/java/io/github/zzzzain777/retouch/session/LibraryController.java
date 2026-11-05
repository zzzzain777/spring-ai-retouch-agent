package io.github.zzzzain777.retouch.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.zzzzain777.retouch.asset.Asset;
import io.github.zzzzain777.retouch.asset.AssetKind;
import io.github.zzzzain777.retouch.asset.AssetOut;
import io.github.zzzzain777.retouch.asset.AssetRepository;
import io.github.zzzzain777.retouch.asset.AssetService;
import io.github.zzzzain777.retouch.auth.CurrentUser;

/** 创作页素材库：按会话收拢图片墙，从没进过会话的上传与生成单独一组。遮罩与抠图层不单独展示。 */
@RestController
@Validated
class LibraryController {

    static final String ORPHAN_TITLE = "未归入会话";
    private static final List<AssetKind> WORKING = List.of(AssetKind.MASK, AssetKind.SUBJECT, AssetKind.BACKGROUND);

    record LibraryGroupOut(UUID sessionId, String title, Instant updatedAt, AssetOut cover, List<AssetOut> assets) {
    }

    private final SessionService sessions;
    private final AssetRepository assetRows;
    private final AssetService assets;

    LibraryController(SessionService sessions, AssetRepository assetRows, AssetService assets) {
        this.sessions = sessions;
        this.assetRows = assetRows;
        this.assets = assets;
    }

    @GetMapping("/api/assets/library")
    @Transactional(readOnly = true)
    List<LibraryGroupOut> library(CurrentUser user,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<EditSession> records = sessions.list(user.id(), limit);
        Map<UUID, List<UUID>> walls = new HashMap<>();
        var attached = new HashSet<UUID>();
        for (EditSession record : records) {
            List<UUID> ids = sessions.wallAssetIds(record);
            walls.put(record.getId(), ids);
            attached.addAll(ids);
        }
        var needed = new HashSet<>(attached);
        records.forEach(record -> needed.add(record.getCurrentAssetId()));
        Map<UUID, Asset> byId = new HashMap<>();
        if (!needed.isEmpty()) {
            assetRows.findByIdInAndUserId(needed, user.id()).forEach(asset -> byId.put(asset.getId(), asset));
        }

        var groups = new ArrayList<LibraryGroupOut>();
        for (EditSession record : records) {
            List<Asset> visible = walls.get(record.getId()).stream().map(byId::get).filter(Objects::nonNull)
                    .filter(asset -> !asset.getKind().working()).toList();
            Asset cover = byId.get(record.getCurrentAssetId());
            if (cover == null && !visible.isEmpty()) {
                cover = visible.getFirst();
            }
            if (cover == null) {
                continue;
            }
            groups.add(new LibraryGroupOut(record.getId(), record.getTitle(), record.getUpdatedAt(),
                    assets.out(cover), (visible.isEmpty() ? List.of(cover) : visible).stream().map(assets::out)
                            .toList()));
        }

        List<Asset> leftover = attached.isEmpty()
                ? assetRows.findByUserIdAndKindNotInOrderByCreatedAtDesc(user.id(), WORKING, Limit.of(limit))
                : assetRows.findByUserIdAndKindNotInAndIdNotInOrderByCreatedAtDesc(user.id(), WORKING, attached,
                        Limit.of(limit));
        if (!leftover.isEmpty()) {
            Asset cover = leftover.getFirst();
            groups.add(new LibraryGroupOut(null, ORPHAN_TITLE, cover.getCreatedAt(), assets.out(cover),
                    leftover.stream().map(assets::out).toList()));
        }
        return groups;
    }
}
