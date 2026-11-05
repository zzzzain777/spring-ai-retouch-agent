package io.github.zzzzain777.retouch.tool;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.common.ApiException;

/**
 * 工具参数的唯一校验入口：先按参数类型转换，再跑 Bean Validation，最后输出规范化后的参数（含未填字段的 null），
 * 手动接口与 Agent 计划校验都调用这里。错误信息格式为“字段：原因”，与上游一致。
 */
@Component
public class ToolParams {

    public record Parsed<P>(P value, Map<String, Object> json) {
    }

    private final JsonMapper json;
    private final Validator validator;

    public ToolParams(JsonMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    public <P> Parsed<P> parse(ToolSpec<P> spec, Map<String, Object> raw) {
        P value;
        try {
            value = json.convertValue(raw == null ? Map.of() : raw, spec.paramsType());
        } catch (JacksonException exc) {
            throw ApiException.unprocessable(field(exc) + "：取值类型不正确");
        }
        var violations = validator.validate(value);
        if (!violations.isEmpty()) {
            var first = violations.stream().min(order(spec.paramsType())).orElseThrow();
            throw ApiException.unprocessable(describe(first));
        }
        Map<String, Object> normalized = json.convertValue(value, new TypeReference<Map<String, Object>>() {
        });
        return new Parsed<>(value, normalized);
    }

    /** 已经校验并存库的参数，执行时再转回参数类型。 */
    public <P> P read(ToolSpec<P> spec, String stored) {
        return json.readValue(stored, spec.paramsType());
    }

    public String write(Map<String, Object> params) {
        return json.writeValueAsString(params);
    }

    private static String describe(ConstraintViolation<?> violation) {
        boolean crossField = violation.getConstraintDescriptor().getAnnotation() instanceof AssertTrue;
        String path = violation.getPropertyPath().toString();
        String field;
        if (crossField) {
            int dot = path.lastIndexOf('.');
            field = dot < 0 ? "参数" : snake(path.substring(0, dot));
        } else {
            field = snake(path);
        }
        return field + "：" + violation.getMessage();
    }

    /** 按参数在记录里的声明顺序报第一个错误，跨字段的组合校验排在最后。 */
    private static Comparator<ConstraintViolation<?>> order(Class<?> type) {
        List<String> names = type.isRecord()
                ? Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList()
                : List.of();
        return Comparator.comparingInt(v -> {
            String head = v.getPropertyPath().iterator().next().getName();
            int index = names.indexOf(head);
            return index < 0 ? Integer.MAX_VALUE : index;
        });
    }

    private static String field(JacksonException exc) {
        if (exc instanceof DatabindException databind && !databind.getPath().isEmpty()) {
            return databind.getPath().stream().map(ref -> ref.getPropertyName() != null ? ref.getPropertyName()
                    : String.valueOf(ref.getIndex())).collect(Collectors.joining("."));
        }
        return "参数";
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
