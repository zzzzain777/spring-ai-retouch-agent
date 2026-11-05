package io.github.zzzzain777.retouch.tool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import io.github.zzzzain777.retouch.asset.AssetKind;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;

/** 需要像素计算的工具：排队后由 Worker 调 Python 图像服务。 */
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

    public static final ToolSpec<AdjustIn> ADJUST_IMAGE = ToolSpec.image("adjust_image", "调色",
            "调整指定图层的亮度、对比度、高光、阴影、色温、色调、饱和度、自然饱和度、锐化、清晰度和晕影。默认最上层图像。"
                    + "参数取值 -1 到 1，晕影为 0 到 1。未提到的参数保持 0。改成某个具体颜色或换材质要用 replace_region，不要用它。",
            AdjustIn.class, false, Set.of(), Duration.ofSeconds(60), true, ImageTools::adjust);

    public static List<ToolSpec<?>> all() {
        return List.of(ADJUST_IMAGE);
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
}
