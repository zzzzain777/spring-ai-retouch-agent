package io.github.zzzzain777.retouch.tool;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RunStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }

    public static class Column extends io.github.zzzzain777.retouch.common.LowercaseEnumConverter<RunStatus> {
        public Column() {
            super(RunStatus.class);
        }
    }
}
