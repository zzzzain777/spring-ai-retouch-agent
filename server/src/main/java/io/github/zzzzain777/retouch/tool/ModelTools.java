package io.github.zzzzain777.retouch.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.github.zzzzain777.retouch.asset.AssetKind;
import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;

/**
 * 需要图像模型（DashScope）的工具：Java 决定改哪一层、用什么提示词、结果放哪；Python 只负责合成像素和调模型。
 * 提示词是本项目自写的。没有配置 DASHSCOPE_API_KEY 时这些工具直接失败并说明原因，不发出任何请求。
 */
public final class ModelTools {

    /** 遮罩由服务端从选区填入（Agent 规划时钉住），不暴露给模型。 */
    static final Set<String> HIDDEN_MASK = Set.of("mask_asset_id", "revision");

    private static final Duration EDIT_TIMEOUT = Duration.ofSeconds(240);

    private ModelTools() {
    }

    // ---- 参数 ----

    public record ReplaceBackgroundIn(
            @JsonPropertyDescription("新背景的描述") @NotBlank @Size(max = 500) String prompt,
            @JsonPropertyDescription("候选张数，1 到 4，默认 1") @Min(1) @Max(4) Integer count,
            @Size(max = 500) String negativePrompt) {

        public ReplaceBackgroundIn {
            count = count == null ? 1 : count;
        }
    }

    static final String EXPAND_DEFAULT = "沿着画面边缘自然延伸，主体完整保留";

    public record ExpandCanvasIn(@NotNull Ratio ratio,
            @JsonPropertyDescription("新增区域怎么补，可不填") @Size(max = 500) String prompt) {

        public ExpandCanvasIn {
            prompt = prompt == null || prompt.isBlank() ? EXPAND_DEFAULT : prompt;
        }
    }

    public record UpscaleImageIn(@JsonPropertyDescription("放大倍数，2 到 4，默认 2") @Min(2) @Max(4) Integer scale) {

        public UpscaleImageIn {
            scale = scale == null ? 2 : scale;
        }
    }

    public record EraseRegionIn(@JsonPropertyDescription(DocumentTools.LAYER_DESC) String layerId,
            @JsonPropertyDescription("可选：补齐时的额外要求") @Size(max = 500) String prompt, UUID maskAssetId,
            Integer revision) {

        public EraseRegionIn {
            prompt = prompt == null ? "" : prompt;
        }
    }

    public record ReplaceRegionIn(@JsonPropertyDescription(DocumentTools.LAYER_DESC) String layerId,
            @JsonPropertyDescription("要改成什么，例如“换成红色皮革”") @NotBlank @Size(max = 500) String prompt,
            UUID maskAssetId, Integer revision) {
    }

    public enum MarketingKind {
        PRODUCT, SCENE, MODEL, POSTER;

        @JsonValue
        public String value() {
            return name().toLowerCase();
        }
    }

    public record MarketingIn(
            @JsonPropertyDescription("product 白底商品主图、scene 生活场景图、model 模特展示图、poster 促销海报") @NotNull MarketingKind kind,
            @JsonPropertyDescription("海报标题或额外要求，可不填") @Size(max = 200) String caption,
            @Min(1) @Max(4) Integer count,
            @JsonPropertyDescription("输出比例，默认 1:1") Ratio ratio) {

        public MarketingIn {
            count = count == null ? 1 : count;
            ratio = ratio == null ? Ratio.SQUARE : ratio;
        }
    }

    public record GenerateIn(@NotBlank @Size(max = 1500) String prompt, Ratio ratio,
            @JsonPropertyDescription("候选张数，1 到 6，默认 4") @Min(1) @Max(6) Integer count,
            @Size(max = 1500) String negativePrompt, @Min(0) @Max(2147483647L) Long seed,
            @Size(max = 3) List<UUID> referenceAssetIds) {

        public GenerateIn {
            prompt = prompt == null ? null : prompt.strip();
            ratio = ratio == null ? Ratio.SQUARE : ratio;
            count = count == null ? 4 : count;
            negativePrompt = negativePrompt == null || negativePrompt.isBlank() ? null : negativePrompt.strip();
            referenceAssetIds = referenceAssetIds == null ? List.of() : List.copyOf(referenceAssetIds);
        }
    }

    // ---- 规格 ----

    public static final ToolSpec<GenerateIn> GENERATE_IMAGE = ToolSpec.sessionless("generate_image", "生成图片",
            "按文字描述从头生成几张候选图。只在没有可编辑的图、或用户明确要一张全新画面时使用；改现有图片不要用它。",
            GenerateIn.class, ToolSpec.Execution.IMAGE, Set.of("seed", "reference_asset_ids"), Duration.ofSeconds(330),
            ModelTools::generate);

    public static final ToolSpec<ReplaceBackgroundIn> REPLACE_BACKGROUND = ToolSpec.image("replace_background",
            "换背景",
            "按描述生成新背景，主体保持不变。画布还没拆层时直接改画布；已经拆层时先合成整张图再生成，结果只放进图片墙。"
                    + "count 为 1 到 4 张候选，多于 1 张时不自动替换，由用户在图片墙里挑选。",
            ReplaceBackgroundIn.class, false, Set.of("negative_prompt"), EDIT_TIMEOUT, false, ModelTools::replaceBackground);

    public static final ToolSpec<ExpandCanvasIn> EXPAND_CANVAS = ToolSpec.image("expand_canvas", "扩图",
            "把画布向外扩到指定比例，新出现的区域由模型自然补全，主体完整保留、不被裁切。",
            ExpandCanvasIn.class, false, Set.of(), EDIT_TIMEOUT, false, ModelTools::expand);

    public static final ToolSpec<UpscaleImageIn> UPSCALE_IMAGE = ToolSpec.image("upscale_image", "超分",
            "放大整个画面并补充细节，scale 为放大倍数（2 到 4，默认 2）。只提高清晰度，不改内容和构图。",
            UpscaleImageIn.class, false, Set.of(), EDIT_TIMEOUT, false, ModelTools::upscale);

    public static final ToolSpec<EraseRegionIn> ERASE_REGION = ToolSpec.image("erase_region", "局部消除",
            "去掉画面里的物体，并用周围的背景自然补齐。有选区时只处理选区；指定 layer_id 时只处理那一层；"
                    + "两者都给时处理它们重叠的部分。",
            EraseRegionIn.class, true, HIDDEN_MASK, EDIT_TIMEOUT, false, ModelTools::erase);

    public static final ToolSpec<ReplaceRegionIn> REPLACE_REGION = ToolSpec.image("replace_region", "局部替换",
            "按描述改画面里的内容：换颜色、换材质、换成别的物体都用它。有选区时只改选区；指定 layer_id 时改整个图层；"
                    + "两者都给时改重叠的部分。只需说明要改成什么，不用确认选区准不准。",
            ReplaceRegionIn.class, true, HIDDEN_MASK, EDIT_TIMEOUT, false, ModelTools::replace);

    public static final ToolSpec<MarketingIn> GENERATE_MARKETING = ToolSpec.image("generate_marketing", "营销图",
            "用当前画面生成电商营销图，结果只放进图片墙，不改画布。kind：product 白底商品主图、scene 生活场景图、"
                    + "model 模特展示图、poster 促销海报；caption 可写海报标题或额外要求。",
            MarketingIn.class, false, Set.of(), EDIT_TIMEOUT, false, ModelTools::marketing);

    public static List<ToolSpec<?>> all() {
        return List.of(GENERATE_IMAGE, REPLACE_BACKGROUND, EXPAND_CANVAS, UPSCALE_IMAGE, ERASE_REGION, REPLACE_REGION,
                GENERATE_MARKETING);
    }

    // ---- 提示词（本项目自写） ----

    static String backgroundPrompt(String background) {
        return "保留商品主体的形状、颜色、光影和边缘，只把背景换成：" + background.strip();
    }

    static final String UPSCALE_PROMPT = "提高清晰度、补充细节，构图、主体和颜色都不要改变，也不要增加任何新元素。";

    static String erasePrompt(String asked, boolean scoped) {
        if (asked != null && !asked.isBlank()) {
            return asked.strip();
        }
        return scoped ? "去掉选区里的物体，用相邻背景的纹理和光线自然补齐，选区以外保持不变。"
                : "去掉这一层里的物体，用相邻背景的纹理和光线自然补齐。";
    }

    static String replacePrompt(String what, boolean scoped) {
        return scoped ? "把选区里的内容改成：" + what.strip() + "。选区以外的画面、光线和透视保持不变。"
                : what.strip() + "。构图、透视和画幅保持不变，原本透明的地方仍然透明。";
    }

    static String marketingPrompt(MarketingKind kind, String caption) {
        String base = switch (kind) {
            case PRODUCT -> "生成电商商品主图：商品居中放在纯白背景上，光线柔和均匀，边缘清晰，商品的材质和颜色保持原样。";
            case SCENE -> "把商品放进一个真实的生活场景里，光线自然、背景略虚化，商品的材质和颜色保持原样。";
            case MODEL -> "生成一位模特使用或佩戴这件商品的展示图，背景简洁、自然光，商品细节清楚。";
            case POSTER -> "设计一张促销海报：商品是视觉中心，画面干净有冲击力，留出一块放文字的空白区域。";
        };
        return caption == null || caption.isBlank() ? base : base + "文案或要求：" + caption.strip();
    }

    // ---- 执行 ----

    static ImageOutcome generate(ImageContext ctx, GenerateIn p) {
        var references = new ArrayList<String>();
        for (UUID id : p.referenceAssetIds()) {
            references.add(ctx.assetKey(id, "参考图不存在"));
        }
        ctx.report(15, "提交生成");
        var body = new LinkedHashMap<String, Object>();
        body.put("prompt", p.prompt());
        body.put("width", p.ratio().width());
        body.put("height", p.ratio().height());
        body.put("count", p.count());
        body.put("negative_prompt", p.negativePrompt());
        body.put("seed", p.seed());
        body.put("reference_keys", references);
        var result = ctx.invokeModel("generate", null, body);
        ctx.report(90, "保存候选图");
        return new ImageOutcome.Candidates(result.outputs());
    }

    static ImageOutcome replaceBackground(ImageContext ctx, ReplaceBackgroundIn p) {
        LayerDocument doc = ctx.document();
        Layer target = Layers.backgroundTarget(doc);
        boolean wallOnly = Layers.layered(doc);
        ctx.report(15, "读取背景");
        Map<String, Object> source = wallOnly || !target.id().equals(LayerDocument.BACKGROUND_LAYER_ID)
                ? Map.of("canvas", ctx.canvas(true))
                : Map.of("key", ctx.layerImageKey(target));
        ctx.report(30, "生成新背景");
        var body = new LinkedHashMap<String, Object>();
        body.put("source", source);
        body.put("prompt", backgroundPrompt(p.prompt()));
        body.put("count", p.count());
        body.put("negative_prompt", p.negativePrompt());
        var result = ctx.invokeModel("model_edit", null, body);
        if (p.count() > 1 || wallOnly) {
            ctx.report(95, "候选已加入图片墙，点选采用");
            return new ImageOutcome.Gallery(result.outputs(), AssetKind.GENERATED, false, List.of());
        }
        AssetKind kind = target.id().equals(LayerDocument.BACKGROUND_LAYER_ID) ? AssetKind.BACKGROUND
                : AssetKind.GENERATED;
        return new ImageOutcome.ReplaceLayerImage(target.id(), result.first(), kind);
    }

    /** 刚好包住原画面的目标比例画幅，扩图时主体不用裁切。 */
    static int[] coverSize(int width, int height, Ratio ratio) {
        int[] parts = ratio.parts();
        long rw = parts[0];
        long rh = parts[1];
        if (width * rh >= height * rw) {
            return new int[] {width, (int) Math.max(1, width * rh / rw)};
        }
        return new int[] {(int) Math.max(1, height * rw / rh), height};
    }

    static ImageOutcome expand(ImageContext ctx, ExpandCanvasIn p) {
        LayerDocument doc = ctx.document();
        int[] size = coverSize(doc.width(), doc.height(), p.ratio());
        ctx.report(15, "读取画布");
        var body = new LinkedHashMap<String, Object>();
        body.put("source", Map.of("canvas", ctx.canvas(true)));
        body.put("prompt", p.prompt());
        body.put("width", size[0]);
        body.put("height", size[1]);
        ctx.report(30, "延伸画幅");
        var result = ctx.invokeModel("model_edit", null, body);
        return new ImageOutcome.Gallery(result.outputs(), AssetKind.GENERATED, !Layers.layered(doc), List.of());
    }

    static ImageOutcome upscale(ImageContext ctx, UpscaleImageIn p) {
        LayerDocument doc = ctx.document();
        ctx.report(15, "读取画布");
        var body = new LinkedHashMap<String, Object>();
        body.put("source", Map.of("canvas", ctx.canvas(true)));
        body.put("prompt", UPSCALE_PROMPT);
        body.put("width", doc.width() * p.scale());
        body.put("height", doc.height() * p.scale());
        ctx.report(30, "提升分辨率");
        var result = ctx.invokeModel("model_edit", null, body);
        return new ImageOutcome.Gallery(List.of(result.first()), AssetKind.GENERATED, !Layers.layered(doc),
                List.of());
    }

    static ImageOutcome erase(ImageContext ctx, EraseRegionIn p) {
        return editRegion(ctx, p.layerId(), p.maskAssetId(), p.revision(),
                scoped -> erasePrompt(p.prompt(), scoped));
    }

    static ImageOutcome replace(ImageContext ctx, ReplaceRegionIn p) {
        return editRegion(ctx, p.layerId(), p.maskAssetId(), p.revision(), scoped -> replacePrompt(p.prompt(), scoped));
    }

    /** 按“图层 × 选区”改一层：有选区只改选区与该层的交集，没有选区改该层整层。 */
    private static ImageOutcome editRegion(ImageContext ctx, String layerId, UUID pinnedMask, Integer revision,
            java.util.function.Function<Boolean, String> prompt) {
        LayerDocument doc = ctx.document();
        if (revision != null && revision != ctx.revision()) {
            throw new ToolError("选区已过期，请重新选择");
        }
        String mask = ctx.selectionMaskKey(pinnedMask);
        Layer layer;
        if ((layerId == null || layerId.isBlank()) && mask != null) {
            layer = Layers.underBox(doc, maskBox(ctx, mask, doc).box());
        } else {
            layer = ctx.target(layerId);
        }
        ctx.report(20, "读取图层");
        var body = new LinkedHashMap<String, Object>();
        body.put("input_key", ctx.layerImageKey(layer));
        body.put("layer", Map.of("x", layer.transform().x(), "y", layer.transform().y(), "width",
                Math.max(1, layer.width()), "height", Math.max(1, layer.height())));
        body.put("canvas_width", doc.width());
        body.put("canvas_height", doc.height());
        body.put("mask_key", mask);
        body.put("prompt", prompt.apply(mask != null));
        ctx.report(40, "局部生成");
        var result = ctx.invokeModel("edit_region", null, body);
        ctx.report(85, "合并结果");
        return new ImageOutcome.ReplaceLayerImage(layer.id(), result.first(), AssetKind.GENERATED, true);
    }

    record MaskBox(int[] box, String digest) {
    }

    static MaskBox maskBox(ImageContext ctx, String maskKey, LayerDocument doc) {
        var meta = ctx.invoke("mask_box", null, Map.of("mask_key", maskKey, "width", doc.width(), "height",
                doc.height())).meta();
        var raw = meta.path("box");
        int[] box = raw.isArray() && raw.size() == 4
                ? new int[] {raw.get(0).asInt(), raw.get(1).asInt(), raw.get(2).asInt(), raw.get(3).asInt()}
                : null;
        return new MaskBox(box, meta.path("digest").asString());
    }

    static ImageOutcome marketing(ImageContext ctx, MarketingIn p) {
        ctx.report(15, "读取画布");
        var body = new LinkedHashMap<String, Object>();
        body.put("source", Map.of("canvas", ctx.canvas(true)));
        body.put("prompt", marketingPrompt(p.kind(), p.caption()));
        body.put("count", p.count());
        body.put("width", p.ratio().width());
        body.put("height", p.ratio().height());
        body.put("fit_width", p.ratio().width());
        body.put("fit_height", p.ratio().height());
        ctx.report(30, "生成营销图");
        var result = ctx.invokeModel("model_edit", null, body);
        var variants = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < result.outputs().size(); i++) {
            variants.add(Map.of("kind", p.kind().value(), "ratio", p.ratio().value(), "width", p.ratio().width(),
                    "height", p.ratio().height()));
        }
        ctx.report(95, "营销图已加入图片墙");
        return new ImageOutcome.Gallery(result.outputs(), AssetKind.MARKETING, false, variants);
    }
}
