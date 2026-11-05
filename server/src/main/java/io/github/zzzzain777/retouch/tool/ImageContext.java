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

    /** 任务开始时会话的修订号。 */
    int revision();

    /** 按 id 或名字取目标图层，找不到时抛可展示的错误。 */
    Layer target(String layerId);

    /** 图像图层素材的对象键；图层没有图像或素材不属于该用户时抛可展示的错误。 */
    String layerImageKey(Layer layer);

    /** 该用户名下某个素材的对象键；不存在或不属于该用户时抛 missing 指定的错误。 */
    String assetKey(UUID assetId, String missing);

    /** 该用户名下某个素材的宽高（像素）。 */
    int[] assetSize(UUID assetId);

    /** 用某个工具的参数规则校验一组参数（批量处理里的每个操作用）；不合法时抛可展示的错误。 */
    <P> P parse(ToolSpec<P> spec, Map<String, Object> raw);

    /**
     * 交给图像服务渲染的画布：尺寸、每个图层的几何与样式、图像层的对象键（已按用户授权解析）。
     * includeText 为 false 时文字层不画进去（文字仍保留为矢量图层）。
     */
    Map<String, Object> canvas(boolean includeText);

    /**
     * 本次要用的选区遮罩的对象键：计划里钉住的遮罩优先，否则取会话当前修订号下的选区；都没有返回 null。
     */
    String selectionMaskKey(UUID pinnedMaskId);

    /** 写进度并推送；如果任务已被请求取消，在这里结束执行。 */
    void report(int progress, String stage);

    /** 调图像服务；输出前缀由任务 id 与第几次执行决定。 */
    List<ImageOutput> call(String op, Map<String, Object> body);

    /** 同上，返回附加信息；subdir 不为空时输出写在本任务前缀下的这个子目录里（批量处理用）。 */
    ImageServiceClient.Result invoke(String op, String subdir, Map<String, Object> body);

    /** 需要图像模型的调用：带上模型地址与模型名，密钥放请求头；没有配置密钥时直接报错，不发请求。 */
    ImageServiceClient.Result invokeModel(String op, String subdir, Map<String, Object> body);
}
