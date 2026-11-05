package io.github.zzzzain777.retouch.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** 唯一的工具注册表：新增工具在这里登记，手动接口与 Agent 同时生效。 */
@Component
public class ToolRegistry {

    private final Map<String, ToolSpec<?>> byName = new LinkedHashMap<>();

    /** 登记顺序与上游一致（也是交给规划模型的顺序）：模型类与像素类在前，批量其次，文档工具最后。 */
    public ToolRegistry() {
        register(ModelTools.GENERATE_IMAGE);
        register(ModelTools.REPLACE_BACKGROUND);
        register(ModelTools.EXPAND_CANVAS);
        register(ModelTools.UPSCALE_IMAGE);
        register(ImageTools.REMOVE_BACKGROUND);
        register(ImageTools.ADJUST_IMAGE);
        register(ModelTools.ERASE_REGION);
        register(ModelTools.REPLACE_REGION);
        LayerTools.all().forEach(this::register);
        register(ModelTools.GENERATE_MARKETING);
        register(ImageTools.PREPARE_DELIVERY_SIZES);
        register(BatchTool.BATCH_PROCESS);
        DocumentTools.all().forEach(this::register);
        // 各分组的 all() 都要登记全：漏登记的工具在这里报出来
        for (var group : List.of(ModelTools.all(), ImageTools.all(), LayerTools.all(), DocumentTools.all())) {
            for (ToolSpec<?> spec : group) {
                if (byName.get(spec.name()) != spec) {
                    throw new IllegalStateException("工具未登记：" + spec.name());
                }
            }
        }
    }

    private void register(ToolSpec<?> spec) {
        if (byName.putIfAbsent(spec.name(), spec) != null) {
            throw new IllegalStateException("工具重复登记：" + spec.name());
        }
    }

    public List<ToolSpec<?>> all() {
        return List.copyOf(byName.values());
    }

    public Optional<ToolSpec<?>> find(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }
}
