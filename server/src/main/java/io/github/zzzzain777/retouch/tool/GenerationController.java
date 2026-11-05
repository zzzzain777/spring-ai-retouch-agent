package io.github.zzzzain777.retouch.tool;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.zzzzain777.retouch.asset.AssetService;
import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.tool.RunViews.RunOut;

/** 文生图：内部提交一个 generate_image 任务（不需要会话），候选图在 GET /api/runs/{id} 的 candidates 里。 */
@RestController
class GenerationController {

    private final ToolService tools;
    private final AssetService assets;
    private final RunViews views;

    GenerationController(ToolService tools, AssetService assets, RunViews views) {
        this.tools = tools;
        this.assets = assets;
        this.views = views;
    }

    @PostMapping("/api/generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RunOut create(CurrentUser user, @RequestBody Map<String, Object> body) {
        Object references = body == null ? null : body.get("reference_asset_ids");
        if (references instanceof Iterable<?> ids) {
            for (Object raw : ids) {
                UUID id;
                try {
                    id = UUID.fromString(String.valueOf(raw));
                } catch (IllegalArgumentException invalid) {
                    throw ApiException.unprocessable("reference_asset_ids：取值类型不正确");
                }
                if (assets.find(user.id(), id).isEmpty()) {
                    throw ApiException.notFound("参考图不存在");
                }
            }
        }
        return views.of(tools.submit(user.id(), ModelTools.GENERATE_IMAGE.name(), body, null, null));
    }
}
