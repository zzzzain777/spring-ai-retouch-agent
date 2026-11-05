package io.github.zzzzain777.retouch.storage;

import java.net.URI;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 图片字节的唯一出入口。业务层只拿对象键，字节只经由这里读写；Python 图像服务按 Java 给的键直接读写同一个桶。
 */
@Component
public class ObjectStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    private final StorageProperties props;
    private final S3Client client;
    private final S3Presigner presigner;

    public ObjectStorage(StorageProperties props) {
        this.props = props;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                // SDK 2.30 起默认用 aws-chunked 加尾部校验和上传；MinIO 以外的兼容存储（moto、部分云厂商）
                // 会把分块编码原样存下。只在协议要求时计算校验和，保证字节原样落盘。
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
    }

    /** 桶不存在时创建；健康检查也调用它。 */
    public void ensureBucket() {
        try {
            client.headBucket(b -> b.bucket(props.bucket()));
        } catch (NoSuchBucketException missing) {
            client.createBucket(b -> b.bucket(props.bucket()));
        }
    }

    /** 启动时建桶；对象存储暂时不可用也不阻止启动，健康检查会报告异常，第一次写入时再补建。 */
    @EventListener(ApplicationReadyEvent.class)
    void ensureBucketOnStartup() {
        try {
            ensureBucket();
        } catch (RuntimeException exc) {
            log.warn("启动时无法确认存储桶 {}：{}", props.bucket(), exc.getClass().getSimpleName());
        }
    }

    public void put(String key, byte[] data, String contentType) {
        try {
            putOnce(key, data, contentType);
        } catch (NoSuchBucketException missing) {
            ensureBucket();
            putOnce(key, data, contentType);
        }
    }

    private void putOnce(String key, byte[] data, String contentType) {
        client.putObject(b -> b.bucket(props.bucket()).key(key).contentType(contentType), RequestBody.fromBytes(data));
    }

    public byte[] get(String key) {
        return client.getObjectAsBytes(b -> b.bucket(props.bucket()).key(key)).asByteArray();
    }

    /** 短时签名 URL：每次读取素材时重新签发，前端不持有长期地址。 */
    public URL presignedUrl(String key) {
        return presigner.presignGetObject(b -> b.signatureDuration(props.urlTtl())
                .getObjectRequest(r -> r.bucket(props.bucket()).key(key))).url();
    }

    public String bucket() {
        return props.bucket();
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
