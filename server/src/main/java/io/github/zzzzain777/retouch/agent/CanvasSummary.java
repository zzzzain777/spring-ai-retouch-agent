package io.github.zzzzain777.retouch.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.zzzzain777.retouch.asset.AssetService;
import io.github.zzzzain777.retouch.selection.SelectionStore;
import io.github.zzzzain777.retouch.session.EditSession;
import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerKind;
import io.github.zzzzain777.retouch.session.SessionService;

/** 给规划模型的画布摘要：只放做决定需要的事实（尺寸、图层、修订号、当前图格式、选区）。 */
@Component
public class CanvasSummary {

    private final SessionService sessions;
    private final AssetService assets;
    private final SelectionStore selections;

    public CanvasSummary(SessionService sessions, AssetService assets, SelectionStore selections) {
        this.sessions = sessions;
        this.assets = assets;
        this.selections = selections;
    }

    public String describe(EditSession record) {
        LayerDocument document = sessions.document(record);
        List<String> parts = new ArrayList<>();
        parts.add("画布 " + document.width() + "×" + document.height());
        parts.add("图层自下而上共 " + document.layers().size() + " 个："
                + document.layers().stream().map(CanvasSummary::layer).collect(Collectors.joining("、")));
        parts.add("layer_id 可填图层 id 或名字，不填时作用在选区下最上层的可见图像，换背景作用在背景层");
        parts.add("修订号 " + record.getRevision());
        assets.find(record.getUserId(), record.getCurrentAssetId()).ifPresent(current -> parts.add(
                "当前图 " + current.getImageFormat() + (current.isHasAlpha() ? "，带透明通道" : "")));
        selections.get(record.getId(), record.getRevision()).ifPresentOrElse(stored -> parts.add(
                (stored.markers().isEmpty() ? "已有笔刷选区" : "已有选区，" + stored.markers().size() + " 个标点")
                        + "（选区只限定区域、不限定图层；同时给了 layer_id 时取两者交集）"),
                () -> parts.add("当前没有选区，区域类工具会作用在整个目标图层"));
        return String.join("；", parts);
    }

    private static String layer(Layer layer) {
        String name = layer.kind() == LayerKind.TEXT && layer.text() != null && !layer.text().isBlank()
                ? "文字「" + head(layer.text(), 8) + "」"
                : layer.name();
        return layer.id() + "「" + name + "」" + (layer.visible() ? "" : "（隐藏）");
    }

    private static String head(String text, int codePoints) {
        int count = text.codePointCount(0, text.length());
        return count <= codePoints ? text : text.substring(0, text.offsetByCodePoints(0, codePoints));
    }
}
