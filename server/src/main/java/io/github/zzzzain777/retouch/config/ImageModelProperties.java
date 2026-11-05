package io.github.zzzzain777.retouch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图像模型（DashScope 原生图像接口）的配置入口。密钥来自 application-local.yml 或环境变量 DASHSCOPE_API_KEY。
 */
@ConfigurationProperties("retouch.image.dashscope")
public record ImageModelProperties(String apiKey, String baseUrl, String textToImageModel, String imageEditModel) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        // 避免密钥出现在日志里
        return "ImageModelProperties[baseUrl=%s, textToImageModel=%s, imageEditModel=%s, apiKey=%s]"
                .formatted(baseUrl, textToImageModel, imageEditModel, configured() ? "***" : "<empty>");
    }
}
