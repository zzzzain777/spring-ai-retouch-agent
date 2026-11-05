package io.github.zzzzain777.retouch.tool;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.github.zzzzain777.retouch.asset.AssetKind;
import io.github.zzzzain777.retouch.session.LayerDocument;

/** 图像工具对会话的改动，由 Worker 在完成事务里应用（锁会话、登记素材、写编辑与历史）。 */
public sealed interface ImageOutcome {

    /** 成功后是否清掉会话的选区（局部编辑、拆层、提升图层用掉了选区）。 */
    default boolean clearsSelection() {
        return false;
    }

    /** 把处理结果写回指定图层（多层时不拍平）；画布只有一张图像时同时设为当前图。 */
    record ReplaceLayerImage(String layerId, ImageOutput output, AssetKind kind, boolean clearSelection)
            implements ImageOutcome {

        public ReplaceLayerImage(String layerId, ImageOutput output, AssetKind kind) {
            this(layerId, output, kind, false);
        }

        @Override
        public boolean clearsSelection() {
            return clearSelection;
        }
    }

    /**
     * 产出放进图片墙。adoptFirst 为 true 时第一张设为当前图（画布换成这张图，修订号加一）；
     * 否则只上墙、画布不变、修订号不变，用户在图片墙里点选采用。variants 与输出一一对应，可为空。
     */
    record Gallery(List<ImageOutput> outputs, AssetKind kind, boolean adoptFirst, List<Map<String, Object>> variants)
            implements ImageOutcome {
    }

    /** 产出的一部分：name 是图像服务给的输出名，kind 是登记成什么素材。 */
    record Part(String name, ImageOutput output, AssetKind kind) {
    }

    /**
     * 改变图层结构（拆层、提升图层）。素材登记后，build 用完成时的文档与“输出名 → 素材 id”生成新文档。
     */
    record Restructure(List<Part> parts,
            BiFunction<LayerDocument, Map<String, java.util.UUID>, LayerDocument> build) implements ImageOutcome {

        @Override
        public boolean clearsSelection() {
            return true;
        }
    }

    /** 没有要改的（例如已经拆过层）：任务成功，不写历史。 */
    record Unchanged(String note) implements ImageOutcome {
    }

    /** 文生图候选：登记为生成素材，不改任何会话。 */
    record Candidates(List<ImageOutput> outputs) implements ImageOutcome {
    }

    /** 批量处理的结果：每张源图一项，产出登记为导出素材。 */
    record Batch(List<BatchItem> items) implements ImageOutcome {
    }

    record BatchItem(String sourceId, String status, String error, List<ImageOutput> outputs) {
    }
}
