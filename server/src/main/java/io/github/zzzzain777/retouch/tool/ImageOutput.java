package io.github.zzzzain777.retouch.tool;

/** 图像服务返回的一个输出对象的元数据；字节已在对象存储里。 */
public record ImageOutput(String key, int width, int height, String imageFormat, boolean hasAlpha, long sizeBytes,
        String sha256) {
}
