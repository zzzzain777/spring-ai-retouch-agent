package io.github.zzzzain777.retouch.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerKind;

/** 把画布文档转成图像服务的渲染入参。图像层的对象键在这里按当前用户解析，别人的素材不画也不外传。 */
final class Canvases {

    private Canvases() {
    }

    static Map<String, Object> payload(LayerDocument doc, Function<UUID, Optional<String>> storageKey,
            boolean includeText) {
        var layers = new ArrayList<Map<String, Object>>();
        for (Layer layer : doc.layers()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("kind", layer.kind().value());
            if (layer.kind() == LayerKind.IMAGE && layer.assetId() != null) {
                storageKey.apply(layer.assetId()).ifPresent(key -> entry.put("key", key));
            }
            entry.put("width", Math.max(1, layer.width()));
            entry.put("height", Math.max(1, layer.height()));
            entry.put("x", layer.transform().x());
            entry.put("y", layer.transform().y());
            entry.put("scale_x", layer.transform().scaleX());
            entry.put("scale_y", layer.transform().scaleY());
            entry.put("rotation", layer.transform().rotation());
            entry.put("opacity", layer.opacity());
            entry.put("visible", layer.visible());
            entry.put("text", layer.text());
            entry.put("font_size", layer.fontSize());
            entry.put("fill", layer.fill());
            layers.add(entry);
        }
        var canvas = new LinkedHashMap<String, Object>();
        canvas.put("width", doc.width());
        canvas.put("height", doc.height());
        canvas.put("layers", layers);
        canvas.put("include_text", includeText);
        return canvas;
    }
}
