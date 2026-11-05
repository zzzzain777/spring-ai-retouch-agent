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

    public ToolRegistry() {
        ImageTools.all().forEach(this::register);
        DocumentTools.all().forEach(this::register);
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
