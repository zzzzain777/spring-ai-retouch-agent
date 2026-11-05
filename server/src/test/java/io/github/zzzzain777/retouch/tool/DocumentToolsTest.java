package io.github.zzzzain777.retouch.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerKind;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerMissing;
import io.github.zzzzain777.retouch.session.LayerDocument.Transform;
import io.github.zzzzain777.retouch.tool.DocumentTools.MoveIn;
import io.github.zzzzain777.retouch.tool.DocumentTools.Place;
import io.github.zzzzain777.retouch.tool.DocumentTools.ReorderIn;
import io.github.zzzzain777.retouch.tool.DocumentTools.RotateIn;
import io.github.zzzzain777.retouch.tool.DocumentTools.ScaleIn;
import io.github.zzzzain777.retouch.tool.DocumentTools.TextIn;

/** 文档变换是纯函数，不需要 Spring 与数据库。 */
class DocumentToolsTest {

    private static Layer image(String id, String name, boolean visible) {
        return new Layer(id, LayerKind.IMAGE, name, 100, 100, UUID.randomUUID(), Transform.IDENTITY, 1, visible,
                false, null, null, 24, "#141414");
    }

    private static Layer text(String id) {
        return new Layer(id, LayerKind.TEXT, "标题", 200, 40, null, Transform.IDENTITY, 1, true, false, null, "旧文案",
                24, "#141414");
    }

    private final LayerDocument doc = new LayerDocument(800, 600,
            List.of(image("background", "背景", true), image("subject", "物体1", true), text("t1"),
                    image("hidden", "隐藏", false)));

    @Test
    void defaultTargetIsTopmostVisibleImageAndNamesResolve() {
        assertThat(doc.resolve(null).id()).isEqualTo("subject");
        assertThat(doc.resolve("物体1").id()).isEqualTo("subject");
        assertThatThrownBy(() -> doc.resolve("nope")).isInstanceOf(LayerMissing.class).hasMessage("图层不存在：nope");
    }

    @Test
    void reorderMovesWithinBounds() {
        var top = DocumentTools.reorder(doc, new ReorderIn("background", Place.TOP));
        assertThat(top.layers().getLast().id()).isEqualTo("background");
        var down = DocumentTools.reorder(doc, new ReorderIn("background", Place.DOWN));
        assertThat(down.layers().getFirst().id()).isEqualTo("background");
        var up = DocumentTools.reorder(doc, new ReorderIn("background", Place.UP));
        assertThat(up.layers().stream().map(Layer::id).toList()).containsExactly("subject", "background", "t1",
                "hidden");
    }

    @Test
    void moveRotateScaleFollowRelativeAndAbsoluteRules() {
        var moved = DocumentTools.move(doc, new MoveIn(null, null, 30.0, 10.0, 5.0));
        assertThat(moved.resolve("subject").transform()).isEqualTo(new Transform(10, 30, 1, 1, 0));

        var rotated = DocumentTools.rotate(DocumentTools.rotate(doc, new RotateIn(null, 30.0, null)),
                new RotateIn(null, 15.0, null));
        assertThat(rotated.resolve("subject").transform().rotation()).isEqualTo(45);
        assertThat(DocumentTools.rotate(rotated, new RotateIn(null, null, -90.0)).resolve("subject").transform()
                .rotation()).isEqualTo(-90);

        var flipped = DocumentTools.flip(doc, new DocumentTools.FlipIn(null, DocumentTools.Direction.HORIZONTAL));
        var scaled = DocumentTools.scale(flipped, new ScaleIn(null, null, 2.0, null, 12.0, null));
        assertThat(scaled.resolve("subject").transform()).isEqualTo(new Transform(12, 0, -2, 1, 0));
        var doubled = DocumentTools.scale(scaled, new ScaleIn(null, 0.5, null, null, null, null));
        assertThat(doubled.resolve("subject").transform().scaleX()).isEqualTo(-1);
        // 原文档不被修改
        assertThat(doc.resolve("subject").transform()).isEqualTo(Transform.IDENTITY);
    }

    @Test
    void setTextTargetsTextLayersOnly() {
        var edited = DocumentTools.setText(doc, new TextIn(null, "夏季新品限时八折优惠进行中", 48.0, "#ff0000"));
        var layer = edited.resolve("t1");
        assertThat(layer.text()).isEqualTo("夏季新品限时八折优惠进行中");
        assertThat(layer.name()).isEqualTo("夏季新品限时八折优惠进行");
        assertThat(layer.fontSize()).isEqualTo(48);
        assertThat(layer.fill()).isEqualTo("#ff0000");
        assertThatThrownBy(() -> DocumentTools.setText(doc, new TextIn("subject", "x", null, null)))
                .isInstanceOf(ToolError.class).hasMessage("只能改文字图层的文案");
    }
}
