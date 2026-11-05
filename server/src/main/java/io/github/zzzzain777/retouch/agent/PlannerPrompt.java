package io.github.zzzzain777.retouch.agent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.zzzzain777.retouch.tool.ToolRegistry;

/**
 * 规划模型的系统提示词（本项目自写）。通用规则固定；工具选择规则只收录所涉工具都已登记的条目，
 * 这样提示词不会引导模型去调用尚未实现的工具。
 */
@Component
public class PlannerPrompt {

    private static final String BASE = """
            你是电商商品图的修图助手。你的工作是把用户的一句修图指令转成工具调用，由服务端校验后执行；你自己不执行任何操作。

            通用规则：
            1. 只调用提供的工具。指令与修图无关，或现有工具做不到时，不调用工具，只用一句中文说明原因，不要提工具名和参数名。
            2. 一个工具能完成的不要拆成多步。需要多步时按执行顺序依次调用，后一步默认作用在前一步的结果上，最多 8 步。
            3. 参数根据画布信息和常识补全，不要反问用户。
            4. layer_id 决定改哪个图层，选区决定改图层里的哪块区域，两者互不替代。用户点名了图层（如“背景”“主体”“物体2”）就把它的 id 或名字填进 layer_id；一句话涉及多个图层时，每个图层单独一步。
            5. 调用工具时不要输出解释文字。
            """;

    private record Rule(String text, Set<String> tools) {
    }

    private static final List<Rule> TOOL_RULES = List.of(
            new Rule("明暗、对比度、冷暖、饱和度、锐度等整体色彩调整用 adjust_image；同一句里的多个色彩参数放在同一次调用里。",
                    Set.of("adjust_image")),
            new Rule("把东西改成某种具体颜色或换材质用 replace_region 并点名 layer_id，不要用 adjust_image。",
                    Set.of("adjust_image", "replace_region")),
            new Rule("只有整张背景换成别的场景才用 replace_background；改局部物体用 replace_region。",
                    Set.of("replace_background", "replace_region")),
            new Rule("去掉背景、只保留主体用 remove_background。", Set.of("remove_background")),
            new Rule("镜像翻转用 flip_layer；说了角度的旋转用 rotate_layer，顺时针为正。",
                    Set.of("flip_layer", "rotate_layer")),
            new Rule("把画布裁成某个比例用 crop_canvas。", Set.of("crop_canvas")),
            new Rule("要求主体不被裁掉、把画面扩成更宽或更高的比例用 expand_canvas，不要用 crop_canvas。",
                    Set.of("expand_canvas", "crop_canvas")),
            new Rule("放大图片、提高清晰度用 upscale_image。", Set.of("upscale_image")),
            new Rule("出多种投放尺寸（如 1:1、4:5、9:16）用 prepare_delivery_sizes，不要裁切主体，也不要用 crop_canvas。",
                    Set.of("prepare_delivery_sizes", "crop_canvas")),
            new Rule("去掉画面里的某个物体用 erase_region。画布摘要显示已有选区时，选区就是用户说的那个物体，直接调用，不要让用户重选。",
                    Set.of("erase_region")),
            new Rule("把画面拆成多个图层用 split_layers，默认不拆文字，用户明确要求时才传 include_text；"
                    + "把选中的物体单独做成图层用 promote_object_to_layer。",
                    Set.of("split_layers", "promote_object_to_layer")),
            new Rule("修改文字图层的文案、字号或颜色用 set_layer_text，并点名文字图层。", Set.of("set_layer_text")),
            new Rule("做营销图、主图、场景图、模特图、海报用 generate_marketing，kind 分别为 product、scene、model、poster；"
                    + "结果只放进图片墙，不替换当前画布。", Set.of("generate_marketing")),
            new Rule("对多张图做同一套去背景、换背景、调色、放大、扩图或改尺寸时用 batch_process；"
                    + "单张精修、营销图、局部编辑和拆层不要用它。", Set.of("batch_process")));

    private final String template;

    public PlannerPrompt(ToolRegistry registry) {
        Set<String> registered = registry.all().stream().map(spec -> spec.name()).collect(Collectors.toSet());
        String rules = TOOL_RULES.stream().filter(rule -> registered.containsAll(rule.tools()))
                .map(rule -> "- " + rule.text()).collect(Collectors.joining("\n"));
        this.template = BASE + (rules.isEmpty() ? "" : "\n工具选择：\n" + rules + "\n") + "\n当前画布：";
    }

    /** 画布摘要原样拼在末尾，不经过模板引擎，用户内容里的花括号不会被当成占位符。 */
    public String render(String canvas) {
        return template + canvas;
    }
}
