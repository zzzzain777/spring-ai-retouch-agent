package io.github.zzzzain777.retouch.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("retouch.image-service")
public record ImageServiceProperties(String baseUrl, String token) {

    @Override
    public String toString() {
        return "ImageServiceProperties[baseUrl=" + baseUrl + ", token=***]";
    }
}
