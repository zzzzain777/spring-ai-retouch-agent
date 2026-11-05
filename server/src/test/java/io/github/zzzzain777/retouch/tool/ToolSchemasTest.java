package io.github.zzzzain777.retouch.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Schema 与参数校验出自同一个参数类型：字段名、必填、范围、枚举取值和隐藏参数都要对得上。 */
class ToolSchemasTest {

    private final ToolRegistry registry = new ToolRegistry();
    private final ToolSchemas schemas = new ToolSchemas(registry);
    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode schema(String tool) {
        return json.readTree(schemas.schema(tool));
    }

    @Test
    void everyRegisteredToolHasAnObjectSchema() {
        for (ToolSpec<?> spec : registry.all()) {
            JsonNode schema = schema(spec.name());
            assertThat(schema.path("type").asString()).as(spec.name()).isEqualTo("object");
            assertThat(schema.has("$schema")).isFalse();
        }
    }

    @Test
    void usesSnakeCaseNamesRangesAndRequiredFromValidation() {
        JsonNode opacity = schema("set_layer_opacity");
        assertThat(opacity.path("properties").has("layer_id")).isTrue();
        assertThat(opacity.path("properties").path("layer_id").path("description").asString()).contains("图层");
        assertThat(opacity.path("properties").path("opacity").path("minimum").asDouble()).isEqualTo(0.0);
        assertThat(opacity.path("properties").path("opacity").path("maximum").asDouble()).isEqualTo(1.0);
        assertThat(opacity.path("required").toString()).contains("opacity").doesNotContain("layer_id");

        JsonNode adjust = schema("adjust_image");
        assertThat(adjust.path("properties").has("brightness")).isTrue();
        assertThat(adjust.path("required").isMissingNode() || adjust.path("required").isEmpty()).isTrue();

        JsonNode text = schema("set_layer_text");
        assertThat(text.path("properties").has("font_size")).isTrue();
        assertThat(text.path("properties").path("fill").path("pattern").asString()).startsWith("^#");
    }

    @Test
    void enumsUseTheirWireValues() {
        assertThat(schema("flip_layer").path("properties").path("direction").path("enum").toString())
                .isEqualTo("[\"horizontal\",\"vertical\"]");
        assertThat(schema("crop_canvas").path("properties").path("ratio").path("enum").toString())
                .contains("\"1:1\"", "\"16:9\"");
        JsonNode rect = schema("crop_canvas").path("properties").path("rect");
        assertThat(rect.path("properties").has("width")).isTrue();
    }

    @Test
    void hiddenParametersAreNotOfferedToTheModel() {
        JsonNode scale = schema("scale_layer");
        assertThat(scale.path("properties").has("factor")).isTrue();
        assertThat(scale.path("properties").has("x")).isFalse();
        assertThat(scale.path("properties").has("y")).isFalse();
    }

    @Test
    void crossFieldChecksAreNotExposedAsProperties() {
        assertThat(schema("move_layer").path("properties").has("target_given")).isFalse();
        assertThat(schema("crop_canvas").path("properties").has("exactly_one")).isFalse();
    }
}
