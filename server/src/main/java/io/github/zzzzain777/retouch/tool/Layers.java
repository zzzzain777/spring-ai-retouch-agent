package io.github.zzzzain777.retouch.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerKind;
import io.github.zzzzain777.retouch.session.LayerDocument.Transform;

/** 拆层、提升图层、换背景等工具对图层结构的判断与改写（只改文档，不碰像素）。语义与上游一致。 */
final class Layers {

    private Layers() {
    }

    /** 已拆过层：同时有背景层与主体层，或有背景层而底图层已经不在。 */
    static boolean alreadySplit(LayerDocument doc) {
        boolean background = has(doc, LayerDocument.BACKGROUND_LAYER_ID);
        return background && (has(doc, LayerDocument.SUBJECT_LAYER_ID) || !has(doc, LayerDocument.BASE_LAYER_ID));
    }

    static boolean alreadyPromoted(LayerDocument doc, String digest) {
        return doc.layers().stream().anyMatch(layer -> digest.equals(layer.sourceHash()));
    }

    /** 已拆层或不止一张图像层：整图类工具的结果只进图片墙，不写回文档。 */
    static boolean layered(LayerDocument doc) {
        return alreadySplit(doc) || imageLayers(doc) > 1;
    }

    static long imageLayers(LayerDocument doc) {
        return doc.layers().stream().filter(layer -> layer.kind() == LayerKind.IMAGE).count();
    }

    /** 换背景的目标：有背景层就是背景层，否则是默认图层。 */
    static Layer backgroundTarget(LayerDocument doc) {
        return doc.layers().stream()
                .filter(l -> l.id().equals(LayerDocument.BACKGROUND_LAYER_ID) && l.kind() == LayerKind.IMAGE)
                .findFirst().orElseGet(() -> doc.resolve(null));
    }

    /** 选区外接框覆盖到的最上层可见图像；没有交集时退回默认图层。box 为 [left, top, right, bottom]。 */
    static Layer underBox(LayerDocument doc, int[] box) {
        if (box == null) {
            return doc.resolve(null);
        }
        List<Layer> layers = doc.layers();
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (layer.kind() != LayerKind.IMAGE || !layer.visible()) {
                continue;
            }
            double x = layer.transform().x();
            double y = layer.transform().y();
            if (box[2] > x && box[0] < x + layer.width() && box[3] > y && box[1] < y + layer.height()) {
                return layer;
            }
        }
        return doc.resolve(null);
    }

    static LayerDocument split(LayerDocument doc, UUID background, UUID subject, String subjectDigest) {
        return doc.withLayers(List.of(backgroundLayer(doc, background),
                image(LayerDocument.SUBJECT_LAYER_ID, "主体", doc.width(), doc.height(), subject, Transform.IDENTITY,
                        false, subjectDigest)));
    }

    /** 未拆层的画布上提升物体：变成“修补后的背景 + 物体1”两层，原有文字层保留在最上面。 */
    static LayerDocument backgroundAndObject(LayerDocument doc, UUID background, UUID object, int x, int y, int w,
            int h, String digest) {
        var layers = new ArrayList<Layer>();
        layers.add(backgroundLayer(doc, background));
        layers.add(image("object-" + digest, "物体1", w, h, object, new Transform(x, y, 1, 1, 0), false, digest));
        doc.layers().stream().filter(l -> l.kind() == LayerKind.TEXT).forEach(layers::add);
        return doc.withLayers(layers);
    }

    /** 已拆层的画布上提升物体：各图像层换成挖洞或修补后的新图，物体追加在最上面，命名为“物体 n”。 */
    static LayerDocument promote(LayerDocument doc, UUID object, int x, int y, int w, int h, String digest,
            Map<String, UUID> replacements) {
        var layers = new ArrayList<Layer>();
        for (Layer layer : doc.layers()) {
            UUID replaced = replacements.get(layer.id());
            layers.add(replaced == null ? layer : layer.withAssetId(replaced));
        }
        // 与上游一致：带来源指纹的图层都算（拆层得到的主体层也有），所以拆层后第一个物体叫“物体2”
        long promoted = doc.layers().stream().filter(l -> l.sourceHash() != null).count();
        layers.add(image("object-" + digest, "物体" + (promoted + 1), w, h, object, new Transform(x, y, 1, 1, 0),
                false, digest));
        return doc.withLayers(layers);
    }

    private static Layer backgroundLayer(LayerDocument doc, UUID asset) {
        return image(LayerDocument.BACKGROUND_LAYER_ID, "背景", doc.width(), doc.height(), asset, Transform.IDENTITY,
                true, null);
    }

    private static Layer image(String id, String name, int w, int h, UUID asset, Transform t, boolean locked,
            String digest) {
        return new Layer(id, LayerKind.IMAGE, name, w, h, asset, t, 1.0, true, locked, digest, null, 24.0, "#141414");
    }

    private static boolean has(LayerDocument doc, String id) {
        return doc.layers().stream().anyMatch(layer -> layer.id().equals(id));
    }
}
