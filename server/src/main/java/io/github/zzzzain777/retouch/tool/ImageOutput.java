package io.github.zzzzain777.retouch.tool;

/** 图像服务返回的一个输出对象的元数据；字节已在对象存储里。name 是输出在本次调用里的用途（如 subject、background）。 */
public record ImageOutput(String key, int width, int height, String imageFormat, boolean hasAlpha, long sizeBytes,
        String sha256, String name) {
}
