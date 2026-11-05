package io.github.zzzzain777.retouch.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.zzzzain777.retouch.asset.Asset;
import io.github.zzzzain777.retouch.asset.AssetService;
import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.session.SessionViews.HistoryOut;
import io.github.zzzzain777.retouch.session.SessionViews.SessionDetailOut;
import io.github.zzzzain777.retouch.session.SessionViews.SessionOut;

@RestController
@RequestMapping("/api/sessions")
@Validated
class SessionController {

    static final int MAX_WALL_ASSETS = 12;

    record SessionCreateIn(@NotNull UUID currentAssetId, @Size(max = MAX_WALL_ASSETS) List<UUID> assetIds,
            String title) {
    }

    record SessionPatchIn(String title, UUID currentAssetId) {
    }

    private final SessionService sessions;
    private final SessionViews views;
    private final AssetService assets;

    SessionController(SessionService sessions, SessionViews views, AssetService assets) {
        this.sessions = sessions;
        this.views = views;
        this.assets = assets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    SessionDetailOut create(CurrentUser user, @Valid @RequestBody SessionCreateIn in) {
        Asset current = assets.require(user.id(), in.currentAssetId(), "素材不存在");
        var extra = new ArrayList<Asset>();
        for (UUID id : in.assetIds() == null ? List.<UUID>of() : in.assetIds()) {
            if (!id.equals(current.getId())) {
                extra.add(assets.require(user.id(), id, "素材不存在"));
            }
        }
        return views.detail(sessions.create(user.id(), current, extra, in.title()));
    }

    @GetMapping
    List<SessionOut> list(CurrentUser user, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return sessions.list(user.id(), limit).stream().map(views::summary).toList();
    }

    @GetMapping("/{sessionId}")
    @Transactional(readOnly = true)
    SessionDetailOut get(CurrentUser user, @PathVariable UUID sessionId) {
        return views.detail(sessions.get(user.id(), sessionId));
    }

    @PatchMapping("/{sessionId}")
    @Transactional
    SessionDetailOut patch(CurrentUser user, @PathVariable UUID sessionId, @RequestBody SessionPatchIn in) {
        EditSession record = sessions.lock(user.id(), sessionId);
        if (in.title() != null) {
            record = sessions.rename(record, in.title());
        }
        if (in.currentAssetId() != null) {
            record = sessions.switchCurrent(record, assets.require(user.id(), in.currentAssetId(), "素材不存在"));
        }
        return views.detail(record);
    }

    @PostMapping("/{sessionId}/undo")
    @Transactional
    SessionDetailOut undo(CurrentUser user, @PathVariable UUID sessionId) {
        return views.detail(sessions.undo(sessions.lock(user.id(), sessionId)));
    }

    @PostMapping("/{sessionId}/redo")
    @Transactional
    SessionDetailOut redo(CurrentUser user, @PathVariable UUID sessionId) {
        return views.detail(sessions.redo(sessions.lock(user.id(), sessionId)));
    }

    @GetMapping("/{sessionId}/history")
    @Transactional(readOnly = true)
    List<HistoryOut> history(CurrentUser user, @PathVariable UUID sessionId) {
        return sessions.history(sessions.get(user.id(), sessionId)).stream().map(views::history).toList();
    }
}
