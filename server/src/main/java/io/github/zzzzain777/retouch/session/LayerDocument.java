package io.github.zzzzain777.retouch.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonValue;

import io.github.zzzzain777.retouch.asset.Asset;

/**
 * 画布文档：工具只改这份文档，像素由渲染环节按文档合成。字段与上游契约一致（JSON 为下划线命名）。
 * 文档是不可变值，每次编辑生成新文档，便于比较“是否真的改了”和写历史快照。
 */
public record LayerDocument(int width, int height, List<Layer> layers) {

    public static final String BASE_LAYER_ID = "base";

    public LayerDocument {
        layers = layers == null ? List.of() : List.copyOf(layers);
    }

    /** 以整张图片作为底图建文档：底图锁定，拆层后才会被主体与背景层取代。 */
    public static LayerDocument of(Asset asset) {
        var base = new Layer(BASE_LAYER_ID, LayerKind.IMAGE, "底图", asset.getWidth(), asset.getHeight(),
                asset.getId(), Transform.IDENTITY, 1.0, true, true, null, null, 24.0, "#141414");
        return new LayerDocument(asset.getWidth(), asset.getHeight(), List.of(base));
    }

    /**
     * 按 id 找图层，找不到再按名称找（Agent 可以说“物体2”）；未指定时取最上层可见图像层。
     * 界面与 Agent 共用这个默认目标。
     */
    public Layer resolve(String layerId) {
        if (layerId != null && !layerId.isBlank()) {
            String wanted = layerId.strip();
            for (Layer layer : layers) {
                if (layer.id().equals(wanted)) {
                    return layer;
                }
            }
            for (Layer layer : layers) {
                if (wanted.equals(layer.name())) {
                    return layer;
                }
            }
            throw new LayerMissing("图层不存在：" + layerId);
        }
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (layer.kind() == LayerKind.IMAGE && layer.visible()) {
                return layer;
            }
        }
        throw new LayerMissing("没有可操作的图层");
    }

    /** 用新图层替换同 id 的图层，其余不变。 */
    public LayerDocument replace(Layer updated) {
        var next = new ArrayList<Layer>(layers.size());
        for (Layer layer : layers) {
            next.add(layer.id().equals(updated.id()) ? updated : layer);
        }
        return new LayerDocument(width, height, next);
    }

    public LayerDocument withLayers(List<Layer> next) {
        return new LayerDocument(width, height, next);
    }

    public LayerDocument withSize(int w, int h) {
        return new LayerDocument(w, h, layers);
    }

    public record Transform(double x, double y, double scaleX, double scaleY, double rotation) {
        public static final Transform IDENTITY = new Transform(0, 0, 1, 1, 0);

        public Transform withPosition(double nx, double ny) {
            return new Transform(nx, ny, scaleX, scaleY, rotation);
        }

        public Transform withScale(double sx, double sy) {
            return new Transform(x, y, sx, sy, rotation);
        }

        public Transform withRotation(double r) {
            return new Transform(x, y, scaleX, scaleY, r);
        }
    }

    public record Layer(String id, LayerKind kind, String name, int width, int height, UUID assetId,
            Transform transform, double opacity, boolean visible, boolean locked, String sourceHash, String text,
            double fontSize, String fill) {

        public Layer {
            transform = transform == null ? Transform.IDENTITY : transform;
        }

        public Layer withTransform(Transform t) {
            return new Layer(id, kind, name, width, height, assetId, t, opacity, visible, locked, sourceHash, text,
                    fontSize, fill);
        }

        public Layer withAssetId(UUID id) {
            return new Layer(this.id, kind, name, width, height, id, transform, opacity, visible, locked, sourceHash,
                    text, fontSize, fill);
        }

        public Layer withOpacity(double o) {
            return new Layer(id, kind, name, width, height, assetId, transform, o, visible, locked, sourceHash, text,
                    fontSize, fill);
        }

        public Layer withVisible(boolean v) {
            return new Layer(id, kind, name, width, height, assetId, transform, opacity, v, locked, sourceHash, text,
                    fontSize, fill);
        }

        public Layer withText(String newName, String newText, double newFontSize, String newFill) {
            return new Layer(id, kind, newName, width, height, assetId, transform, opacity, visible, locked,
                    sourceHash, newText, newFontSize, newFill);
        }
    }

    public enum LayerKind {
        IMAGE, TEXT, SHAPE;

        @JsonValue
        public String value() {
            return name().toLowerCase();
        }
    }

    public static class LayerMissing extends RuntimeException {
        public LayerMissing(String message) {
            super(message);
        }
    }
}
