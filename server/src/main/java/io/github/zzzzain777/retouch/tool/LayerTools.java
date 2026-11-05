package io.github.zzzzain777.retouch.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Size;

import io.github.zzzzain777.retouch.asset.AssetKind;
import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerKind;

/** 改变图层结构的工具：拆层、把选区里的物体提升为图层。抠图在本地，补背景用图像模型。 */
public final class LayerTools {

    static final String REPAIR_PROMPT = "图中有一块已经用周围颜色补平的区域，把它修得和四周的纹理、光照自然衔接；"
            + "不要在那里画出新的人或物体，其他地方保持原样。";

    private LayerTools() {
    }

    public record SplitLayersIn(
            @JsonPropertyDescription("是否同时拆出文字层，默认 false，用户明确要求时才设为 true") Boolean includeText) {

        public SplitLayersIn {
            includeText = includeText != null && includeText;
        }
    }

    public record PromoteIn(@JsonPropertyDescription("新图层的名字，可不填") @Size(max = 40) String name,
            UUID maskAssetId, Integer revision) {
    }

    public static final ToolSpec<SplitLayersIn> SPLIT_LAYERS = ToolSpec.image("split_layers", "拆层",
            "把画面拆成背景层和主体层，背景里被主体挡住的部分会补全。默认不识别文字；include_text 为 true 时才尝试拆出文字层。"
                    + "已经拆过层时不再重复。",
            SplitLayersIn.class, false, Set.of(), Duration.ofSeconds(240), false, LayerTools::split);

    public static final ToolSpec<PromoteIn> PROMOTE_OBJECT_TO_LAYER = ToolSpec.image("promote_object_to_layer",
            "提升为图层",
            "把选区里的物体单独做成一个图层，原来的位置用周围背景补上。需要先有选区，画布摘要写明已有选区时就可以调用；"
                    + "这个物体已经是单独图层时不再重复。",
            PromoteIn.class, true, ModelTools.HIDDEN_MASK, Duration.ofSeconds(240), false, LayerTools::promote);

    public static List<ToolSpec<?>> all() {
        return List.of(SPLIT_LAYERS, PROMOTE_OBJECT_TO_LAYER);
    }

    static ImageOutcome split(ImageContext ctx, SplitLayersIn p) {
        LayerDocument doc = ctx.document();
        if (Layers.alreadySplit(doc)) {
            return new ImageOutcome.Unchanged("已经拆过层");
        }
        if (p.includeText()) {
            // 文字识别（OCR）还没有接入；直接说明，而不是悄悄拆成不含文字的两层
            throw new ToolError("暂不支持拆出文字层，可以先不拆文字再试");
        }
        ctx.report(15, "读取画布");
        var body = new LinkedHashMap<String, Object>();
        body.put("canvas", ctx.canvas(true));
        body.put("prompt", REPAIR_PROMPT);
        ctx.report(35, "分离主体并修复背景");
        var result = ctx.invokeModel("split", null, body);
        String digest = result.meta().path("subject_digest").asString();
        var parts = List.of(new ImageOutcome.Part("background", result.named("background"), AssetKind.BACKGROUND),
                new ImageOutcome.Part("subject", result.named("subject"), AssetKind.SUBJECT));
        return new ImageOutcome.Restructure(parts,
                (current, ids) -> Layers.split(current, ids.get("background"), ids.get("subject"), digest));
    }

    static ImageOutcome promote(ImageContext ctx, PromoteIn p) {
        LayerDocument doc = ctx.document();
        if (p.revision() != null && p.revision() != ctx.revision()) {
            throw new ToolError("选区已过期，请重新选择");
        }
        String mask = ctx.selectionMaskKey(p.maskAssetId());
        if (mask == null) {
            throw new ToolError("请先点选或涂抹要拆出的物体");
        }
        ModelTools.MaskBox box = ModelTools.maskBox(ctx, mask, doc);
        if (box.box() == null) {
            throw new ToolError("选区为空，请重新选择");
        }
        if (Layers.alreadyPromoted(doc, box.digest())) {
            return new ImageOutcome.Unchanged("这个物体已经是单独的图层");
        }
        boolean split = Layers.alreadySplit(doc);
        var layers = new ArrayList<Map<String, Object>>();
        if (split) {
            for (Layer layer : doc.layers()) {
                if (layer.kind() != LayerKind.IMAGE || layer.assetId() == null) {
                    continue;
                }
                layers.add(Map.of("id", layer.id(), "key", ctx.layerImageKey(layer), "x", layer.transform().x(), "y",
                        layer.transform().y(), "background", layer.id().equals(LayerDocument.BACKGROUND_LAYER_ID)));
            }
        }
        ctx.report(20, "读取选区");
        var body = new LinkedHashMap<String, Object>();
        body.put("canvas", ctx.canvas(false));
        body.put("mask_key", mask);
        body.put("layers", layers);
        body.put("prompt", REPAIR_PROMPT);
        ctx.report(45, "修复原位置");
        var result = ctx.invokeModel("promote", null, body);
        var meta = result.meta();
        int x = meta.path("x").asInt();
        int y = meta.path("y").asInt();
        int w = meta.path("width").asInt();
        int h = meta.path("height").asInt();
        String digest = meta.path("digest").asString();

        var parts = new ArrayList<ImageOutcome.Part>();
        parts.add(new ImageOutcome.Part("object", result.named("object"), AssetKind.SUBJECT));
        if (split) {
            for (Map<String, Object> layer : layers) {
                String id = (String) layer.get("id");
                boolean background = (Boolean) layer.get("background");
                parts.add(new ImageOutcome.Part(id, result.named("layer-" + id.toLowerCase()),
                        background ? AssetKind.BACKGROUND : AssetKind.SUBJECT));
            }
        } else {
            parts.add(new ImageOutcome.Part("background", result.named("background"), AssetKind.BACKGROUND));
        }
        String name = p.name() == null || p.name().isBlank() ? null : p.name().strip();
        return new ImageOutcome.Restructure(parts, (current, ids) -> {
            LayerDocument next;
            if (split) {
                var replacements = new LinkedHashMap<String, UUID>(ids);
                replacements.remove("object");
                next = Layers.promote(current, ids.get("object"), x, y, w, h, digest, replacements);
            } else {
                next = Layers.backgroundAndObject(current, ids.get("background"), ids.get("object"), x, y, w, h,
                        digest);
            }
            if (name != null) {
                List<Layer> updated = new ArrayList<>(next.layers());
                int last = -1;
                for (int i = 0; i < updated.size(); i++) {
                    if (updated.get(i).id().equals("object-" + digest)) {
                        last = i;
                    }
                }
                if (last >= 0) {
                    updated.set(last, updated.get(last).withName(name));
                    next = next.withLayers(updated);
                }
            }
            return next;
        });
    }
}
