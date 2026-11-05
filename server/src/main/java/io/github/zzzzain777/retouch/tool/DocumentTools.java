package io.github.zzzzain777.retouch.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerKind;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerMissing;

/**
 * 只改画布文档的 9 个工具（上游 #14–#22）：参数类型、校验规则与文档变换。变换是纯函数，返回新文档。
 */
public final class DocumentTools {

    static final String LAYER_DESC = "要修改的图层 id 或名字，如 background、subject、物体2。不填则作用在选区下最上层可见图像。";
    static final int MIN_CROP = 32;

    private DocumentTools() {
    }

    public enum Direction {
        HORIZONTAL, VERTICAL;

        @JsonValue
        public String value() {
            return name().toLowerCase();
        }
    }

    public enum Place {
        TOP, BOTTOM, UP, DOWN;

        @JsonValue
        public String value() {
            return name().toLowerCase();
        }
    }

    public record FlipIn(@JsonPropertyDescription(LAYER_DESC) String layerId, @NotNull Direction direction) {
    }

    public record OpacityIn(@JsonPropertyDescription(LAYER_DESC) String layerId,
            @NotNull @DecimalMin("0") @DecimalMax("1") Double opacity) {
    }

    public record VisibleIn(@JsonPropertyDescription(LAYER_DESC) String layerId, @NotNull Boolean visible) {
    }

    public record TextIn(@JsonPropertyDescription(LAYER_DESC) String layerId, @NotNull @Size(max = 500) String text,
            @DecimalMin("8") @DecimalMax("400") Double fontSize,
            @Pattern(regexp = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$") String fill) {
    }

    public record ScaleIn(@JsonPropertyDescription(LAYER_DESC) String layerId,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("8") Double factor,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("8") Double scaleX,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("8") Double scaleY, Double x, Double y) {

        @JsonIgnore
        @AssertTrue(message = "需要相对倍率或绝对缩放")
        public boolean isTargetGiven() {
            return factor != null || scaleX != null || scaleY != null;
        }
    }

    public record RotateIn(@JsonPropertyDescription(LAYER_DESC) String layerId,
            @DecimalMin("-360") @DecimalMax("360") Double angle,
            @DecimalMin("-360") @DecimalMax("360") Double rotation) {

        @JsonIgnore
        @AssertTrue(message = "需要旋转角度")
        public boolean isAngleGiven() {
            return angle != null || rotation != null;
        }
    }

    public record ReorderIn(@JsonPropertyDescription(LAYER_DESC) String layerId, @NotNull Place place) {
    }

    public record MoveIn(@JsonPropertyDescription(LAYER_DESC) String layerId, Double x, Double y, Double dx,
            Double dy) {

        @JsonIgnore
        @AssertTrue(message = "需要坐标或位移")
        public boolean isTargetGiven() {
            return x != null || y != null || dx != null || dy != null;
        }
    }

    public record CropRect(@NotNull @DecimalMin("0") @DecimalMax("1") Double x,
            @NotNull @DecimalMin("0") @DecimalMax("1") Double y,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double width,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double height) {

        @JsonIgnore
        @AssertTrue(message = "裁剪框超出画布")
        public boolean isInside() {
            return x == null || y == null || width == null || height == null
                    || (x + width <= 1.0001 && y + height <= 1.0001);
        }
    }

    public record CropIn(Ratio ratio, @Valid CropRect rect) {

        @JsonIgnore
        @AssertTrue(message = "裁剪需要比例或裁剪框之一")
        public boolean isExactlyOne() {
            return (ratio == null) != (rect == null);
        }
    }

    public static final ToolSpec<CropIn> CROP_CANVAS = ToolSpec.document("crop_canvas", "裁剪",
            "按比例或归一化矩形裁切画布。只改图层文档，不调用生成模型。", CropIn.class, Set.of(), null, DocumentTools::crop);
    public static final ToolSpec<FlipIn> FLIP_LAYER = ToolSpec.document("flip_layer", "翻转",
            "水平或垂直翻转指定图层，默认最上层图像。", FlipIn.class, Set.of(),
            p -> p.direction() == null ? null : p.direction() == Direction.HORIZONTAL ? "水平翻转" : "垂直翻转",
            DocumentTools::flip);
    public static final ToolSpec<OpacityIn> SET_LAYER_OPACITY = ToolSpec.document("set_layer_opacity", "透明度",
            "设置图层透明度，取值 0 到 1。", OpacityIn.class, Set.of(), null,
            (doc, p) -> doc.replace(doc.resolve(p.layerId()).withOpacity(p.opacity())));
    public static final ToolSpec<VisibleIn> SET_LAYER_VISIBLE = ToolSpec.document("set_layer_visible", "显隐",
            "显示或隐藏指定图层，不删除内容。", VisibleIn.class, Set.of(), null,
            (doc, p) -> doc.replace(doc.resolve(p.layerId()).withVisible(p.visible())));
    public static final ToolSpec<TextIn> SET_LAYER_TEXT = ToolSpec.document("set_layer_text", "改文字",
            "修改文字图层的文案，可选字号与颜色。未指定图层时改最上层可见文字。", TextIn.class, Set.of(), null,
            DocumentTools::setText);
    public static final ToolSpec<ReorderIn> REORDER_LAYER = ToolSpec.document("reorder_layer", "图层顺序",
            "调整图层前后顺序：top / bottom / up / down。", ReorderIn.class, Set.of(), null, DocumentTools::reorder);
    public static final ToolSpec<ScaleIn> SCALE_LAYER = ToolSpec.document("scale_layer", "缩放",
            "缩放指定图层，默认最上层图像。factor 为相对倍率，scale_x / scale_y 为绝对值。", ScaleIn.class,
            Set.of("x", "y"), null, DocumentTools::scale);
    public static final ToolSpec<RotateIn> ROTATE_LAYER = ToolSpec.document("rotate_layer", "旋转",
            "旋转图层。angle 为相对角度，rotation 为绝对角度，顺时针为正。", RotateIn.class, Set.of(), null,
            DocumentTools::rotate);
    public static final ToolSpec<MoveIn> MOVE_LAYER = ToolSpec.document("move_layer", "移动",
            "移动指定图层。x/y 为绝对坐标，dx/dy 为相对像素位移。默认最上层图像。", MoveIn.class, Set.of(), null,
            DocumentTools::move);

    public static List<ToolSpec<?>> all() {
        return List.of(CROP_CANVAS, FLIP_LAYER, SET_LAYER_OPACITY, SET_LAYER_VISIBLE, SET_LAYER_TEXT, REORDER_LAYER,
                SCALE_LAYER, ROTATE_LAYER, MOVE_LAYER);
    }

    /** 就地翻转：x/y 表示未缩放框的左上角，负缩放不移动位置。 */
    static LayerDocument flip(LayerDocument doc, FlipIn p) {
        Layer layer = doc.resolve(p.layerId());
        var t = layer.transform();
        var flipped = p.direction() == Direction.HORIZONTAL ? t.withScale(-t.scaleX(), t.scaleY())
                : t.withScale(t.scaleX(), -t.scaleY());
        return doc.replace(layer.withTransform(flipped));
    }

    static LayerDocument setText(LayerDocument doc, TextIn p) {
        Layer layer = textLayer(doc, p.layerId());
        String name = p.text().isEmpty() ? layer.name()
                : p.text().substring(0, p.text().offsetByCodePoints(0, Math.min(12, p.text().codePointCount(0,
                        p.text().length()))));
        return doc.replace(layer.withText(name, p.text(), p.fontSize() == null ? layer.fontSize() : p.fontSize(),
                p.fill() == null ? layer.fill() : p.fill()));
    }

    private static Layer textLayer(LayerDocument doc, String layerId) {
        if (layerId != null && !layerId.isBlank()) {
            Layer layer = doc.resolve(layerId);
            if (layer.kind() != LayerKind.TEXT) {
                throw new ToolError("只能改文字图层的文案");
            }
            return layer;
        }
        for (int i = doc.layers().size() - 1; i >= 0; i--) {
            Layer layer = doc.layers().get(i);
            if (layer.kind() == LayerKind.TEXT && layer.visible()) {
                return layer;
            }
        }
        throw new LayerMissing("没有可编辑的文字图层");
    }

    /** factor 为相对倍率；scale_x / scale_y 为绝对值并保留已有翻转方向；x/y 随拖角缩放一并写入。 */
    static LayerDocument scale(LayerDocument doc, ScaleIn p) {
        Layer layer = doc.resolve(p.layerId());
        var t = layer.transform();
        t = t.withPosition(p.x() != null ? p.x() : t.x(), p.y() != null ? p.y() : t.y());
        if (p.factor() != null) {
            t = t.withScale(t.scaleX() * p.factor(), t.scaleY() * p.factor());
        } else {
            t = t.withScale(p.scaleX() != null ? withSign(t.scaleX(), p.scaleX()) : t.scaleX(),
                    p.scaleY() != null ? withSign(t.scaleY(), p.scaleY()) : t.scaleY());
        }
        return doc.replace(layer.withTransform(t));
    }

    static LayerDocument rotate(LayerDocument doc, RotateIn p) {
        Layer layer = doc.resolve(p.layerId());
        var t = layer.transform();
        double next = p.angle() != null ? t.rotation() + p.angle() : p.rotation() == null ? 0 : p.rotation();
        return doc.replace(layer.withTransform(t.withRotation(next)));
    }

    static LayerDocument move(LayerDocument doc, MoveIn p) {
        Layer layer = doc.resolve(p.layerId());
        var t = layer.transform();
        double x = p.x() != null ? p.x() : t.x() + (p.dx() == null ? 0 : p.dx());
        double y = p.y() != null ? p.y() : t.y() + (p.dy() == null ? 0 : p.dy());
        return doc.replace(layer.withTransform(t.withPosition(x, y)));
    }

    /** 层表末尾最后绘制，即视觉上的最前。 */
    static LayerDocument reorder(LayerDocument doc, ReorderIn p) {
        Layer target = doc.resolve(p.layerId());
        var layers = new ArrayList<>(doc.layers());
        int index = -1;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).id().equals(target.id())) {
                index = i;
                break;
            }
        }
        switch (p.place()) {
            case TOP -> layers.add(layers.remove(index));
            case BOTTOM -> layers.addFirst(layers.remove(index));
            case UP -> {
                if (index < layers.size() - 1) {
                    java.util.Collections.swap(layers, index, index + 1);
                }
            }
            case DOWN -> {
                if (index > 0) {
                    java.util.Collections.swap(layers, index, index - 1);
                }
            }
        }
        return doc.withLayers(layers);
    }

    /** 裁切画布：图层只平移，像素仍由渲染时按画布边界裁剪。 */
    static LayerDocument crop(LayerDocument doc, CropIn p) {
        double x;
        double y;
        double width;
        double height;
        if (p.ratio() != null) {
            int[] parts = p.ratio().parts();
            if ((long) doc.width() * parts[1] >= (long) doc.height() * parts[0]) {
                height = doc.height();
                width = height * parts[0] / parts[1];
            } else {
                width = doc.width();
                height = width * parts[1] / parts[0];
            }
            x = (doc.width() - width) / 2;
            y = (doc.height() - height) / 2;
        } else {
            x = p.rect().x() * doc.width();
            y = p.rect().y() * doc.height();
            width = p.rect().width() * doc.width();
            height = p.rect().height() * doc.height();
        }
        // 与上游一致用“四舍六入五成双”取整
        int left = Math.max(0, (int) Math.rint(x));
        int top = Math.max(0, (int) Math.rint(y));
        int right = Math.min(doc.width(), (int) Math.rint(x + width));
        int bottom = Math.min(doc.height(), (int) Math.rint(y + height));
        int w = Math.max(0, right - left);
        int h = Math.max(0, bottom - top);
        if (w < MIN_CROP || h < MIN_CROP) {
            throw new ToolError("裁剪区域过小，最短边需不小于 " + MIN_CROP + " 像素");
        }
        var layers = doc.layers().stream().map(layer -> layer.withTransform(
                layer.transform().withPosition(layer.transform().x() - left, layer.transform().y() - top))).toList();
        return new LayerDocument(w, h, layers);
    }

    private static double withSign(double current, double magnitude) {
        return Math.abs(magnitude) * (current >= 0 ? 1 : -1);
    }
}
