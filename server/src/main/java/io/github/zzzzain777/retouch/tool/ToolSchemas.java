package io.github.zzzzain777.retouch.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 交给规划模型的工具参数 Schema，直接由 {@link ToolSpec#paramsType()} 生成：字段名与接口一致用下划线，
 * 必填、取值范围、长度与格式取自同一组 Bean Validation 注解，字段说明取自 {@code @JsonPropertyDescription}。
 * 服务端从上下文填入的参数（{@link ToolSpec#agentHidden()}）不出现在 Schema 里。
 */
@Component
public class ToolSchemas {

    private final SchemaGenerator generator;
    private final Map<String, String> byTool = new LinkedHashMap<>();

    public ToolSchemas(ToolRegistry registry) {
        var config = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonSchemaModule(JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE))
                .with(new JakartaValidationModule(JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
                .without(Option.SCHEMA_VERSION_INDICATOR);
        config.forFields().withPropertyNameOverrideResolver(field -> ToolParams.snake(field.getDeclaredName()));
        // 保持参数在记录里的声明顺序（图层在前），不按字母重排
        config.forTypesInGeneral().withPropertySorter((first, second) -> 0);
        this.generator = new SchemaGenerator(config.build());
        for (ToolSpec<?> spec : registry.all()) {
            byTool.put(spec.name(), build(spec).toString());
        }
    }

    /** 工具参数的 JSON Schema 文本（object 类型）。 */
    public String schema(String tool) {
        String schema = byTool.get(tool);
        if (schema == null) {
            throw new IllegalArgumentException("未注册的工具：" + tool);
        }
        return schema;
    }

    private ObjectNode build(ToolSpec<?> spec) {
        ObjectNode schema = generator.generateSchema(spec.paramsType());
        JsonNode properties = schema.get("properties");
        if (properties instanceof ObjectNode props) {
            spec.agentHidden().forEach(props::remove);
        } else {
            schema.putObject("properties");
        }
        if (schema.get("required") instanceof ArrayNode required) {
            for (int i = required.size() - 1; i >= 0; i--) {
                if (spec.agentHidden().contains(required.get(i).asString())) {
                    required.remove(i);
                }
            }
        }
        return schema;
    }
}
