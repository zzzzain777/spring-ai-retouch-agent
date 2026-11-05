package io.github.zzzzain777.retouch.asset;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AssetSource {
    UPLOAD, GENERATE, TOOL;

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }
}
