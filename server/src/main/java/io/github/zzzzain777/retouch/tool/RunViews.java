package io.github.zzzzain777.retouch.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.asset.AssetOut;
import io.github.zzzzain777.retouch.asset.AssetService;

@Component
public class RunViews {

    /** 与上游 RunOut 一致；candidates 是结果里的候选图，签名 URL 每次读取重新签发。 */
    public record RunOut(UUID id, String tool, RunStatus status, int progress, String stage, String error,
            String prompt, List<AssetOut> candidates, JsonNode result) {
    }

    private final JsonMapper json;
    private final AssetService assets;

    RunViews(JsonMapper json, AssetService assets) {
        this.json = json;
        this.assets = assets;
    }

    public RunOut of(ToolRun run) {
        return of(run, false);
    }

    public RunOut withCandidates(ToolRun run) {
        return of(run, true);
    }

    private RunOut of(ToolRun run, boolean withCandidates) {
        JsonNode params = json.readTree(run.getParams());
        JsonNode result = json.readTree(run.getResult());
        var candidates = new ArrayList<AssetOut>();
        if (withCandidates) {
            for (JsonNode raw : result.path("asset_ids")) {
                try {
                    assets.find(run.getUserId(), UUID.fromString(raw.asString()))
                            .ifPresent(asset -> candidates.add(assets.out(asset)));
                } catch (IllegalArgumentException ignored) {
                    // 结果里不是合法 id 的条目忽略
                }
            }
        }
        JsonNode prompt = params.get("prompt");
        return new RunOut(run.getId(), run.getTool(), run.getStatus(), run.getProgress(), run.getStage(),
                run.getError(), prompt == null || prompt.isNull() ? null : prompt.asString(), candidates, result);
    }
}
