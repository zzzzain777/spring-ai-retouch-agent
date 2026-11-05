package io.github.zzzzain777.retouch.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.zzzzain777.retouch.asset.Asset;
import io.github.zzzzain777.retouch.asset.AssetKind;
import io.github.zzzzain777.retouch.asset.AssetRepository;
import io.github.zzzzain777.retouch.asset.AssetService;
import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.session.EditSession;
import io.github.zzzzain777.retouch.session.SessionService;
import io.github.zzzzain777.retouch.storage.ObjectStorage;
import tools.jackson.databind.json.JsonMapper;

/**
 * 会话素材导出（与上游语义一致）：POST /api/sessions/{id}/exports，响应是 zip。
 * 指定 asset_ids 时按给定顺序导出这些素材（必须都在本会话的图片墙里，否则 422）；
 * 不指定时导出营销图与投放图；一张都没有时导出当前画布的拼平图。当前图对应的条目总是现渲染的画布。
 * zip 里带 manifest.json：标题、会话 id、时间与每个文件的类型、比例、尺寸、素材 id。
 */
@RestController
public class ExportController {

    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(60);

    private record Entry(Asset asset, byte[] data, int width, int height, String extension) {
        boolean flattened() {
            return asset == null;
        }
    }

    public record ExportIn(List<UUID> assetIds) {

        public ExportIn {
            assetIds = assetIds == null ? List.of() : assetIds;
        }
    }

    private final SessionService sessions;
    private final AssetService assets;
    private final AssetRepository assetRows;
    private final ImageServiceClient images;
    private final ObjectStorage storage;
    private final JsonMapper json;

    ExportController(SessionService sessions, AssetService assets, AssetRepository assetRows,
            ImageServiceClient images, ObjectStorage storage, JsonMapper json) {
        this.sessions = sessions;
        this.assets = assets;
        this.assetRows = assetRows;
        this.images = images;
        this.storage = storage;
        this.json = json;
    }

    @PostMapping("/api/sessions/{sessionId}/exports")
    ResponseEntity<byte[]> export(CurrentUser user, @PathVariable UUID sessionId,
            @RequestBody(required = false) ExportIn in) {
        EditSession record = sessions.get(user.id(), sessionId);
        List<UUID> requested = (in == null ? new ExportIn(null) : in).assetIds();

        List<UUID> wallIds = sessions.wallAssetIds(record);
        var wall = new HashMap<UUID, Asset>();
        assetRows.findByIdInAndUserId(wallIds, user.id()).forEach(asset -> wall.put(asset.getId(), asset));

        List<Asset> chosen = new ArrayList<>();
        if (!requested.isEmpty()) {
            for (UUID id : requested) {
                Asset asset = wall.get(id);
                if (asset == null) {
                    throw ApiException.unprocessable("素材不在该会话中");
                }
                chosen.add(asset);
            }
        } else {
            for (UUID id : wallIds) {
                Asset asset = wall.get(id);
                if (asset != null && (asset.getKind() == AssetKind.MARKETING || asset.getKind() == AssetKind.EXPORT)) {
                    chosen.add(asset);
                }
            }
        }

        var entries = new ArrayList<Entry>();
        if (chosen.isEmpty()) {
            entries.add(renderCanvas(user, record));
        } else {
            for (Asset asset : chosen) {
                if (asset.getId().equals(record.getCurrentAssetId())) {
                    entries.add(renderCanvas(user, record));
                } else {
                    entries.add(new Entry(asset, assets.bytes(asset), asset.getWidth(), asset.getHeight(),
                            extension(asset.getImageFormat())));
                }
            }
        }

        Instant createdAt = Instant.now();
        var items = new ArrayList<Map<String, Object>>();
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            int index = 0;
            for (Entry entry : entries) {
                index++;
                String kind = entry.flattened() ? "canvas" : entry.asset().getKind().name().toLowerCase();
                String ratio = ratioOf(entry.width(), entry.height());
                String slug = ratio != null ? ratio.replace(":", "x") : entry.width() + "x" + entry.height();
                String name = "images/%02d-%s-%s.%s".formatted(index, kind, slug, entry.extension());
                zip.putNextEntry(new ZipEntry(name));
                zip.write(entry.data());
                zip.closeEntry();
                var item = new LinkedHashMap<String, Object>();
                item.put("file", name);
                item.put("kind", kind);
                item.put("ratio", ratio);
                item.put("width", entry.width());
                item.put("height", entry.height());
                item.put("asset_id", entry.flattened() ? null : entry.asset().getId().toString());
                items.add(item);
            }
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("title", record.getTitle());
            manifest.put("session_id", record.getId().toString());
            manifest.put("created_at", DateTimeFormatter.ISO_INSTANT.format(createdAt));
            manifest.put("items", items);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(createdAt);
        String filename = safeName(record.getTitle()) + "-" + date + ".zip";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(buffer.toByteArray());
    }

    /** 当前画布的拼平图：由图像服务渲染，PNG。渲染是读操作，不建任务也不改会话。 */
    private Entry renderCanvas(CurrentUser user, EditSession record) {
        var canvas = Canvases.payload(sessions.document(record),
                id -> assets.find(user.id(), id).map(Asset::getStorageKey), true);
        UUID renderId = UUID.randomUUID();
        var body = new LinkedHashMap<String, Object>();
        body.put("output_prefix", ImageServiceClient.outputPrefix(renderId, 1));
        body.put("canvas", canvas);
        try {
            ImageOutput output = images.invoke("render", renderId, 1, body, RENDER_TIMEOUT, null).first();
            return new Entry(null, storage.get(output.key()), output.width(), output.height(), "png");
        } catch (ToolError failure) {
            throw ApiException.conflict("画布渲染失败：" + failure.getMessage());
        }
    }

    private static String extension(String imageFormat) {
        return switch (imageFormat == null ? "" : imageFormat.toUpperCase()) {
            case "JPEG", "JPG" -> "jpg";
            case "WEBP" -> "webp";
            default -> "png";
        };
    }

    /** 宽高对应到标准比例：先对交付尺寸，再按整数比例判断；都不符合返回 null。 */
    private static String ratioOf(int width, int height) {
        for (Ratio ratio : Ratio.values()) {
            if (width == ratio.width() && height == ratio.height()) {
                return ratio.value();
            }
        }
        for (Ratio ratio : Ratio.values()) {
            int[] parts = ratio.parts();
            if ((long) width * parts[1] == (long) height * parts[0]) {
                return ratio.value();
            }
        }
        return null;
    }

    private static String safeName(String title) {
        String cleaned = (title == null ? "" : title).replaceAll("[^\\w\\u4e00-\\u9fff\\- ]+", "").strip()
                .replace(' ', '-');
        if (cleaned.isEmpty()) {
            cleaned = "物料";
        }
        if (cleaned.codePointCount(0, cleaned.length()) > 40) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, 40));
        }
        return cleaned;
    }
}
