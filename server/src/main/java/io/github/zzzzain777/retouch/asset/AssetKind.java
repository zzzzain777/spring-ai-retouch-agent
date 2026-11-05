package io.github.zzzzain777.retouch.asset;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AssetKind {
    ORIGINAL, GENERATED, SUBJECT, BACKGROUND, MASK, MARKETING, EXPORT;

    /** 遮罩与抠图层是会话内部产物，素材库不单独展示。 */
    public boolean working() {
        return this == MASK || this == SUBJECT || this == BACKGROUND;
    }

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }
}
