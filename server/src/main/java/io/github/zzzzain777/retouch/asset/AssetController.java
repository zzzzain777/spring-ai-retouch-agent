package io.github.zzzzain777.retouch.asset;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.common.ApiException;

@RestController
@RequestMapping("/api/assets")
@Validated
class AssetController {

    private final AssetService assets;

    AssetController(AssetService assets) {
        this.assets = assets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AssetOut upload(CurrentUser user, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > ImageProbe.MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "文件超过 20 MB 上限");
        }
        return assets.out(assets.createFromBytes(user.id(), file.getBytes(), AssetKind.ORIGINAL, AssetSource.UPLOAD));
    }

    @GetMapping
    List<AssetOut> list(CurrentUser user, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return assets.recent(user.id(), limit).stream().map(assets::out).toList();
    }

    @GetMapping("/{assetId}")
    AssetOut get(CurrentUser user, @PathVariable UUID assetId) {
        return assets.out(assets.require(user.id(), assetId, "素材不存在"));
    }
}
