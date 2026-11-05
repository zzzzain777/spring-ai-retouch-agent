package io.github.zzzzain777.retouch.tool;

import com.fasterxml.jackson.annotation.JsonValue;

/** 画幅比例，取值与上游一致。 */
public enum Ratio {
    SQUARE("1:1", 1080, 1080),
    PORTRAIT_4_5("4:5", 1080, 1350),
    PORTRAIT_3_4("3:4", 1080, 1440),
    VERTICAL_9_16("9:16", 1080, 1920),
    LANDSCAPE_16_9("16:9", 1920, 1080);

    private final String value;
    private final int width;
    private final int height;

    Ratio(String value, int width, int height) {
        this.value = value;
        this.width = width;
        this.height = height;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** 比例的两个整数部分，例如 16:9 返回 {16, 9}。 */
    public int[] parts() {
        String[] split = value.split(":");
        return new int[] {Integer.parseInt(split[0]), Integer.parseInt(split[1])};
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
