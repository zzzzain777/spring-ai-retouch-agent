package io.github.zzzzain777.retouch.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import io.github.zzzzain777.retouch.asset.AssetKind;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;

/** 本地像素工具：排队后由 Worker 调 Python 图像服务，不调用图像模型，不产生费用。 */
public final class ImageTools {

    private ImageTools() {
    }

    /** 未提到的参数为 0（不变），与上游一致；规范化后的参数里也写 0。 */
    public record AdjustIn(@JsonPropertyDescription(DocumentTools.LAYER_DESC) String layerId,
            @DecimalMin("-1") @DecimalMax("1") Double brightness, @DecimalMin("-1") @DecimalMax("1") Double contrast,
            @DecimalMin("-1") @DecimalMax("1") Double highlights, @DecimalMin("-1") @DecimalMax("1") Double shadows,
            @DecimalMin("-1") @DecimalMax("1") Double temperature, @DecimalMin("-1") @DecimalMax("1") Double tint,
            @DecimalMin("-1") @DecimalMax("1") Double saturation, @DecimalMin("-1") @DecimalMax("1") Double vibrance,
            @DecimalMin("-1") @DecimalMax("1") Double sharpness, @DecimalMin("-1") @DecimalMax("1") Double clarity,
            @DecimalMin("0") @DecimalMax("1") Double vignette) {

        public AdjustIn {
            brightness = zero(brightness);
            contrast = zero(contrast);
            highlights = zero(highlights);
            shadows = zero(shadows);
            temperature = zero(temperature);
            tint = zero(tint);
            saturation = zero(saturation);
            vibrance = zero(vibrance);
            sharpness = zero(sharpness);
            clarity = zero(clarity);
            vignette = zero(vignette);
        }

        private static Double zero(Double value) {
            return value == null ? 0.0 : value;
        }

        Map<String, Object> values() {
            var map = new LinkedHashMap<String, Object>();
            map.put("brightness", brightness);
            map.put("contrast", contrast);
            map.put("highlights", highlights);
            map.put("shadows", shadows);
            map.put("temperature", temperature);
            map.put("tint", tint);
            map.put("saturation", saturation);
            map.put("vibrance", vibrance);
            map.put("sharpness", sharpness);
            map.put("clarity", clarity);
            map.put("vignette", vignette);
            return map;
        }
    }

    public record RemoveBackgroundIn(@JsonPropertyDescription(DocumentTools.LAYER_DESC) String layerId) {
    }

    public static final List<Ratio> DELIVERY_DEFAULT = List.of(Ratio.SQUARE, Ratio.PORTRAIT_4_5, Ratio.VERTICAL_9_16);

    public record DeliveryIn(
            @JsonPropertyDescription("要出的投放比例，默认 1:1、4:5、9:16；画面完整保留，不裁切。") @Size(min = 1, max = 5) List<Ratio> ratios) {

        public DeliveryIn {
            ratios = ratios == null ? DELIVERY_DEFAULT : ratios;
        }

        /** 去重并保持顺序。 */
        List<Ratio> unique() {
            return List.copyOf(new LinkedHashSet<>(ratios));
        }
    }

    public static final ToolSpec<AdjustIn> ADJUST_IMAGE = ToolSpec.image("adjust_image", "调色",
            "对一个图层做整体色彩与影调调整：亮度、对比度、高光、阴影、色温、色调、饱和度、自然饱和度、锐化、清晰度"
                    + "（取值 -1 到 1）和暗角（0 到 1）；不指定图层时作用于最上层可见图像，没提到的项保持 0。"
                    + "要把某样东西改成具体颜色或换材质，用 replace_region。",
            AdjustIn.class, false, Set.of(), Duration.ofSeconds(60), true, ImageTools::adjust);

    public static final ToolSpec<RemoveBackgroundIn> REMOVE_BACKGROUND = ToolSpec.image("remove_background", "去背景",
            "抠出图层里的主体，把背景变成透明；不指定图层时作用于最上层可见图像。只去掉背景，不会生成新背景，"
                    + "要换成别的背景用 replace_background。",
            RemoveBackgroundIn.class, false, Set.of(), Duration.ofSeconds(120), true, ImageTools::removeBackground);

    public static final ToolSpec<DeliveryIn> PREPARE_DELIVERY_SIZES = ToolSpec.image("prepare_delivery_sizes",
            "投放尺寸",
            "把当前画面完整地等比放进各个投放比例，四周用白色补边，不裁切、不改内容，结果放进图片墙。"
                    + "用户要改尺寸、出投放图时用它，不要用 crop_canvas。",
            DeliveryIn.class, false, Set.of(), Duration.ofSeconds(60), true, ImageTools::delivery);

    public static List<ToolSpec<?>> all() {
        return List.of(REMOVE_BACKGROUND, ADJUST_IMAGE, PREPARE_DELIVERY_SIZES);
    }

    static ImageOutcome adjust(ImageContext ctx, AdjustIn p) {
        Layer layer = ctx.target(p.layerId());
        ctx.report(20, "读取图层");
        String key = ctx.layerImageKey(layer);
        ctx.report(60, "调整色彩");
        List<ImageOutput> outputs = ctx.call("adjust", Map.of("input_key", key, "params", p.values()));
        if (outputs.isEmpty()) {
            throw new ToolError("图像服务没有返回结果");
        }
        return new ImageOutcome.ReplaceLayerImage(layer.id(), outputs.getFirst(), AssetKind.GENERATED);
    }

    static ImageOutcome removeBackground(ImageContext ctx, RemoveBackgroundIn p) {
        Layer layer = ctx.target(p.layerId());
        ctx.report(20, "识别主体");
        String key = ctx.layerImageKey(layer);
        ctx.report(50, "去除背景");
        ImageOutput subject = ctx.invoke("remove_background", null, Map.of("input_key", key)).named("subject");
        return new ImageOutcome.ReplaceLayerImage(layer.id(), subject, AssetKind.SUBJECT);
    }

    static ImageOutcome delivery(ImageContext ctx, DeliveryIn p) {
        List<Ratio> ratios = p.unique();
        ctx.report(20, "读取画布");
        var sizes = new ArrayList<Map<String, Object>>();
        var variants = new ArrayList<Map<String, Object>>();
        for (Ratio ratio : ratios) {
            sizes.add(Map.of("width", ratio.width(), "height", ratio.height()));
            variants.add(Map.of("kind", "export", "ratio", ratio.value(), "width", ratio.width(), "height",
                    ratio.height()));
        }
        ctx.report(50, "适配投放尺寸");
        var result = ctx.invoke("delivery", null, Map.of("source", Map.of("canvas", ctx.canvas(true)), "sizes", sizes));
        ctx.report(95, "投放尺寸已加入图片墙");
        return new ImageOutcome.Gallery(result.outputs(), AssetKind.EXPORT, false, variants);
    }
}
