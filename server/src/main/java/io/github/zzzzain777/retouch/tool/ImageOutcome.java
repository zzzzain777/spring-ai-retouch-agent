package io.github.zzzzain777.retouch.tool;

import io.github.zzzzain777.retouch.asset.AssetKind;

/** 图像工具对会话的改动，由 Worker 在完成事务里应用。 */
public sealed interface ImageOutcome {

    /** 把处理结果写回指定图层（多层时不拍平）；画布只有一张图像时同时设为当前图。 */
    record ReplaceLayerImage(String layerId, ImageOutput output, AssetKind kind) implements ImageOutcome {
    }
}
