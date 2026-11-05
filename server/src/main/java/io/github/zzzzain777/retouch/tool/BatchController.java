package io.github.zzzzain777.retouch.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.asset.Asset;
import io.github.zzzzain777.retouch.asset.AssetOut;
import io.github.zzzzain777.retouch.asset.AssetService;
import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.tool.RunViews.RunOut;

/** 批量处理接口：一次批量就是一个 batch_process 任务，路径与响应结构与上游一致。 */
@RestController
@RequestMapping("/api/batches")
class BatchController {

    record BatchItemOut(AssetOut source, String status, String error, List<AssetOut> outputs) {
    }

    record BatchOut(RunOut run, List<BatchItemOut> items, Instant createdAt) {
    }

    private final ToolService tools;
    private final ToolRunRepository runs;
    private final RunViews views;
    private final AssetService assets;
    private final JsonMapper json;

    BatchController(ToolService tools, ToolRunRepository runs, RunViews views, AssetService assets, JsonMapper json) {
        this.tools = tools;
        this.runs = runs;
        this.views = views;
        this.assets = assets;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    RunOut create(CurrentUser user, @RequestBody Map<String, Object> body) {
        // 先按批量参数整体校验（422），再查素材归属（404），再逐个校验操作参数（422），最后才建任务
        BatchTool.BatchIn parsed = json.convertValue(tools.validate(BatchTool.BATCH_PROCESS.name(), body),
                BatchTool.BatchIn.class);
        for (UUID id : parsed.assetIds()) {
            if (assets.find(user.id(), id).isEmpty()) {
                throw ApiException.notFound("素材不存在");
            }
        }
        for (int i = 0; i < parsed.operations().size(); i++) {
            BatchTool.BatchOp op = parsed.operations().get(i);
            try {
                tools.validate(op.tool().value(), op.params());
            } catch (ApiException invalid) {
                throw ApiException.unprocessable("operations." + i + "：" + invalid.getMessage());
            }
        }
        return views.of(tools.submit(user.id(), BatchTool.BATCH_PROCESS.name(), body, null, null));
    }

    @GetMapping
    List<BatchOut> list(CurrentUser user, @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 50) {
            throw ApiException.unprocessable("limit：取值需在 1 到 50 之间");
        }
        return runs.findByUserIdAndToolOrderByCreatedAtDesc(user.id(), BatchTool.BATCH_PROCESS.name(), Limit.of(limit))
                .stream().map(run -> detail(user, run)).toList();
    }

    @GetMapping("/{runId}")
    BatchOut get(CurrentUser user, @PathVariable UUID runId) {
        return detail(user, find(user, runId));
    }

    @GetMapping("/{runId}/export")
    ResponseEntity<byte[]> export(CurrentUser user, @PathVariable UUID runId) {
        ToolRun run = find(user, runId);
        if (run.getStatus() != RunStatus.SUCCEEDED) {
            throw ApiException.conflict("还没有可打包的结果");
        }
        List<Asset> outputs = new ArrayList<>();
        for (JsonNode item : json.readTree(run.getResult()).path("items")) {
            for (JsonNode id : item.path("output_ids")) {
                assets.find(user.id(), UUID.fromString(id.asString())).ifPresent(outputs::add);
            }
        }
        if (outputs.isEmpty()) {
            throw ApiException.conflict("还没有可打包的结果");
        }
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            int index = 0;
            for (Asset asset : outputs) {
                index++;
                String ext = "JPEG".equals(asset.getImageFormat()) ? "jpg"
                        : asset.getImageFormat().toLowerCase();
                zip.putNextEntry(new ZipEntry("images/%02d-%dx%d.%s".formatted(index, asset.getWidth(),
                        asset.getHeight(), ext)));
                zip.write(assets.bytes(asset));
                zip.closeEntry();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        String name = "batch-" + runId.toString().substring(0, 8) + ".zip";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(buffer.toByteArray());
    }

    private ToolRun find(CurrentUser user, UUID runId) {
        return runs.findByIdAndUserId(runId, user.id()).filter(r -> r.getTool().equals(BatchTool.BATCH_PROCESS.name()))
                .orElseThrow(() -> ApiException.notFound("任务不存在"));
    }

    private BatchOut detail(CurrentUser user, ToolRun run) {
        JsonNode result = json.readTree(run.getResult());
        var items = new ArrayList<BatchItemOut>();
        JsonNode raw = result.path("items");
        if (raw.isArray() && !raw.isEmpty()) {
            for (JsonNode item : raw) {
                Asset source = asset(user, item.path("source_id").asString());
                if (source == null) {
                    continue;
                }
                var outputs = new ArrayList<AssetOut>();
                for (JsonNode id : item.path("output_ids")) {
                    Asset output = asset(user, id.asString());
                    if (output != null) {
                        outputs.add(assets.out(output));
                    }
                }
                JsonNode error = item.path("error");
                items.add(new BatchItemOut(assets.out(source), item.path("status").asString("pending"),
                        error.isNull() || error.isMissingNode() ? null : error.asString(), outputs));
            }
        } else {
            for (JsonNode id : json.readTree(run.getParams()).path("asset_ids")) {
                Asset source = asset(user, id.asString());
                if (source != null) {
                    items.add(new BatchItemOut(assets.out(source), "pending", null, List.of()));
                }
            }
        }
        return new BatchOut(views.of(run), items, run.getCreatedAt());
    }

    private Asset asset(CurrentUser user, String id) {
        try {
            return assets.find(user.id(), UUID.fromString(id)).orElse(null);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
