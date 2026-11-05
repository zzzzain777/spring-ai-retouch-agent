package io.github.zzzzain777.retouch.tool;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;

/** 图像工具执行时能用的能力。实现在 Worker 里，每个方法都按任务所属用户做授权。 */
public interface ImageContext {

    UUID runId();

    int attempt();

    /** 任务开始时的画布文档。 */
    LayerDocument document();

    /** 按 id 或名字取目标图层，找不到时抛可展示的错误。 */
    Layer target(String layerId);

    /** 图像图层素材的对象键；图层没有图像或素材不属于该用户时抛可展示的错误。 */
    String layerImageKey(Layer layer);

    /** 写进度并推送；如果任务已被请求取消，在这里结束执行。 */
    void report(int progress, String stage);

    /** 调图像服务；输出前缀由任务 id 与第几次执行决定。 */
    List<ImageOutput> call(String op, Map<String, Object> body);
}
