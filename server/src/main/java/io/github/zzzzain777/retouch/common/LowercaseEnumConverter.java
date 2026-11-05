package io.github.zzzzain777.retouch.common;

import jakarta.persistence.AttributeConverter;

/** 库里枚举存小写值（与对外 JSON 一致），不用数据库原生枚举类型。 */
public abstract class LowercaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected LowercaseEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : value.name().toLowerCase();
    }

    @Override
    public E convertToEntityAttribute(String value) {
        return value == null ? null : Enum.valueOf(type, value.toUpperCase());
    }
}
