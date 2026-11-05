package io.github.zzzzain777.retouch.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("retouch.auth")
public record AuthProperties(String jwtSecret, Duration ttl, boolean secureCookie) {

    @Override
    public String toString() {
        return "AuthProperties[ttl=%s, secureCookie=%s]".formatted(ttl, secureCookie);
    }
}
