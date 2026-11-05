package io.github.zzzzain777.retouch.auth;

import java.util.UUID;

/** 已通过 Cookie 令牌认证、且在库中存在的用户。所有业务查询都以 {@link #id()} 过滤所有者。 */
public record CurrentUser(UUID id, String username) {
}
