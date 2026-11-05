package io.github.zzzzain777.retouch.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("retouch.storage")
public record StorageProperties(
        String endpoint, String region, String bucket, String accessKey, String secretKey, Duration urlTtl) {

    @Override
    public String toString() {
        return "StorageProperties[endpoint=%s, region=%s, bucket=%s]".formatted(endpoint, region, bucket);
    }
}
