package io.github.zzzzain777.retouch.asset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import io.github.zzzzain777.retouch.common.ApiException;

/**
 * 上传校验：只读文件头拿格式、尺寸与是否含透明通道，不解码整张图。规则与上游一致：
 * JPEG / PNG / WebP，不超过 20 MB，最短边不小于 32 像素，总像素不超过 5000 万。
 */
@Component
public class ImageProbe {

    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    static final long MAX_PIXELS = 50_000_000L;
    static final int MIN_SIDE = 32;

    public ImageMeta probe(byte[] data) {
        if (data.length == 0) {
            throw ApiException.unprocessable("文件为空");
        }
        if (data.length > MAX_FILE_BYTES) {
            throw new ApiException(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, "文件超过 20 MB 上限");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw ApiException.unprocessable("文件已损坏或不是受支持的图片");
            }
            ImageReader reader = readers.next();
            try {
                ImageFormat format = formatOf(reader.getFormatName());
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (Math.min(width, height) < MIN_SIDE) {
                    throw ApiException.unprocessable("图片过小，最短边需不小于 " + MIN_SIDE + " 像素");
                }
                if ((long) width * height > MAX_PIXELS) {
                    throw ApiException.unprocessable("图片像素总量过大");
                }
                return new ImageMeta(format, width, height, data.length, hasAlpha(reader));
            } finally {
                reader.dispose();
            }
        } catch (IOException exc) {
            throw ApiException.unprocessable("文件已损坏或不是受支持的图片");
        }
    }

    private static ImageFormat formatOf(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> ImageFormat.JPEG;
            case "png" -> ImageFormat.PNG;
            case "webp" -> ImageFormat.WEBP;
            default -> throw ApiException.unprocessable("仅支持 JPG、PNG 与 WebP");
        };
    }

    private static boolean hasAlpha(ImageReader reader) throws IOException {
        ImageTypeSpecifier raw = reader.getRawImageType(0);
        if (raw != null) {
            return raw.getColorModel().hasAlpha();
        }
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        return types.hasNext() && types.next().getColorModel().hasAlpha();
    }
}
