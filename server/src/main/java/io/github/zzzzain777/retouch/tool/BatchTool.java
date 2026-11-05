package io.github.zzzzain777.retouch.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量处理：对最多 20 张图依次执行同一组操作（6 种），每张图的结果按格式导出。调度在 Java：
 * 每一步都是一次图像服务调用，上一步的输出对象就是下一步的输入，输出各自放在本任务前缀下的子目录里。
 * 一张图失败只记在这一项上，其余继续；全部失败时任务失败。
 */
public final class BatchTool {

    public static final int MAX_ASSETS = 20;

    private BatchTool() {
    }

    public enum Operation {
        REMOVE_BACKGROUND, REPLACE_BACKGROUND, ADJUST_IMAGE, UPSCALE_IMAGE, EXPAND_CANVAS, PREPARE_DELIVERY_SIZES;

        @JsonValue
        public String value() {
            return name().toLowerCase();
        }

        boolean needsModel() {
            return this == REPLACE_BACKGROUND || this == UPSCALE_IMAGE || this == EXPAND_CANVAS;
        }
    }

    public enum Format {
        PNG, JPG;

        @JsonValue
        public String value() {
            return name().toLowerCase();
        }
    }

    public record BatchOp(@NotNull Operation tool, Map<String, Object> params) {

        public BatchOp {
            params = params == null ? Map.of() : params;
        }
    }

    public record BatchIn(@NotEmpty @Size(max = MAX_ASSETS) List<@NotNull UUID> assetIds,
            @NotEmpty @Size(max = 6) List<@Valid @NotNull BatchOp> operations,
            @Size(min = 1, max = 2) List<@NotNull Format> formats) {

        public BatchIn {
            formats = formats == null ? List.of(Format.PNG) : formats;
        }
    }

    public static final ToolSpec<BatchIn> BATCH_PROCESS = ToolSpec.sessionless("batch_process", "批量处理",
            "对多张图依次做同一组处理：去背景、换背景、调色、放大、扩图、出投放尺寸。只用于多张图的统一处理；"
                    + "单张精修、营销图、局部编辑和拆层都不要用它。",
            BatchIn.class, ToolSpec.Execution.COMPOSITE, Set.of(), Duration.ofMinutes(60), BatchTool::run);

    /** 一帧：对象键与尺寸。一张源图经“投放尺寸”后会变成多帧。 */
    private record Frame(String key, int width, int height) {
    }

    /** 每个操作的参数，执行前统一校验（规则与单张工具相同）。 */
    private record Step(Operation op, Object params) {
    }

    static ImageOutcome run(ImageContext ctx, BatchIn p) {
        List<Step> steps = checkedSteps(ctx, p);
        var items = new ArrayList<ImageOutcome.BatchItem>();
        int total = p.assetIds().size();
        ctx.report(8, "准备批量任务");
        for (int index = 0; index < total; index++) {
            UUID source = p.assetIds().get(index);
            ctx.report(10 + 80 * index / total, "处理第 " + (index + 1) + " / " + total + " 张");
            try {
                items.add(new ImageOutcome.BatchItem(source.toString(), "succeeded", null,
                        process(ctx, index + 1, source, steps, p.formats())));
            } catch (ToolError failure) {
                items.add(new ImageOutcome.BatchItem(source.toString(), "failed", failure.getMessage(), List.of()));
            }
        }
        if (items.stream().allMatch(item -> item.status().equals("failed"))) {
            throw new ToolError(items.getFirst().error() == null ? "全部处理失败" : items.getFirst().error());
        }
        ctx.report(95, "批量处理完成");
        return new ImageOutcome.Batch(items);
    }

    private static List<Step> checkedSteps(ImageContext ctx, BatchIn p) {
        var steps = new ArrayList<Step>();
        for (int i = 0; i < p.operations().size(); i++) {
            BatchOp op = p.operations().get(i);
            ToolSpec<?> spec = switch (op.tool()) {
                case REMOVE_BACKGROUND -> ImageTools.REMOVE_BACKGROUND;
                case REPLACE_BACKGROUND -> ModelTools.REPLACE_BACKGROUND;
                case ADJUST_IMAGE -> ImageTools.ADJUST_IMAGE;
                case UPSCALE_IMAGE -> ModelTools.UPSCALE_IMAGE;
                case EXPAND_CANVAS -> ModelTools.EXPAND_CANVAS;
                case PREPARE_DELIVERY_SIZES -> ImageTools.PREPARE_DELIVERY_SIZES;
            };
            try {
                steps.add(new Step(op.tool(), ctx.parse(spec, op.params())));
            } catch (ToolError invalid) {
                throw new ToolError("第 " + (i + 1) + " 个操作：" + invalid.getMessage());
            }
        }
        return steps;
    }

    private static List<ImageOutput> process(ImageContext ctx, int item, UUID source, List<Step> steps,
            List<Format> formats) {
        String key = ctx.assetKey(source, "素材不存在");
        var size = ctx.assetSize(source);
        List<Frame> frames = List.of(new Frame(key, size[0], size[1]));
        for (int s = 0; s < steps.size(); s++) {
            var next = new ArrayList<Frame>();
            for (int f = 0; f < frames.size(); f++) {
                String dir = "i%d-o%d-f%d/".formatted(item, s + 1, f + 1);
                for (ImageOutput out : apply(ctx, dir, steps.get(s), frames.get(f))) {
                    next.add(new Frame(out.key(), out.width(), out.height()));
                }
            }
            frames = next;
        }
        var outputs = new ArrayList<ImageOutput>();
        int n = 0;
        for (Frame frame : frames) {
            for (Format format : formats) {
                n++;
                var body = Map.<String, Object>of("input_key", frame.key(), "name", "export-" + n, "image_format",
                        format == Format.JPG ? "JPEG" : "PNG");
                outputs.add(ctx.invoke("encode", "i%d-out/".formatted(item), body).first());
            }
        }
        return outputs;
    }

    private static List<ImageOutput> apply(ImageContext ctx, String dir, Step step, Frame frame) {
        return switch (step.op()) {
            case REMOVE_BACKGROUND -> List.of(
                    ctx.invoke("remove_background", dir, Map.of("input_key", frame.key())).named("subject"));
            case ADJUST_IMAGE -> List.of(ctx.invoke("adjust", dir,
                    Map.of("input_key", frame.key(), "params", ((ImageTools.AdjustIn) step.params()).values())).first());
            case UPSCALE_IMAGE -> {
                int scale = ((ModelTools.UpscaleImageIn) step.params()).scale();
                yield List.of(ctx.invokeModel("model_edit", dir, Map.of("source", Map.of("key", frame.key()), "prompt",
                        ModelTools.UPSCALE_PROMPT, "width", frame.width() * scale, "height", frame.height() * scale))
                        .first());
            }
            case REPLACE_BACKGROUND -> {
                var params = (ModelTools.ReplaceBackgroundIn) step.params();
                var body = new LinkedHashMap<String, Object>();
                body.put("source", Map.of("key", frame.key()));
                body.put("prompt", ModelTools.backgroundPrompt(params.prompt()));
                body.put("count", 1);
                body.put("negative_prompt", params.negativePrompt());
                yield List.of(ctx.invokeModel("model_edit", dir, body).first());
            }
            case EXPAND_CANVAS -> {
                var params = (ModelTools.ExpandCanvasIn) step.params();
                int[] target = ModelTools.coverSize(frame.width(), frame.height(), params.ratio());
                yield List.of(ctx.invokeModel("model_edit", dir, Map.of("source", Map.of("key", frame.key()), "prompt",
                        params.prompt(), "width", target[0], "height", target[1])).first());
            }
            case PREPARE_DELIVERY_SIZES -> {
                var sizes = ((ImageTools.DeliveryIn) step.params()).unique().stream()
                        .map(r -> Map.<String, Object>of("width", r.width(), "height", r.height())).toList();
                yield ctx.invoke("delivery", dir, Map.of("source", Map.of("key", frame.key()), "sizes", sizes))
                        .outputs();
            }
        };
    }
}
