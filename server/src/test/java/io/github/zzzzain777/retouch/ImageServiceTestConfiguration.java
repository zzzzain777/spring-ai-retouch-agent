package io.github.zzzzain777.retouch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/** 在 Testcontainers 的 S3（moto）上启动真实 Python 图像服务，并把 Java 的图像服务地址指过去。 */
@TestConfiguration(proxyBeanMethods = false)
public class ImageServiceTestConfiguration {

    @Bean(destroyMethod = "close")
    ImageServiceProcess imageServiceProcess(GenericContainer<?> s3) throws Exception {
        return ImageServiceProcess.start("http://" + s3.getHost() + ":" + s3.getMappedPort(5000));
    }

    @Bean
    DynamicPropertyRegistrar imageServiceProperties(ImageServiceProcess process) {
        return registry -> {
            registry.add("retouch.image-service.base-url", process::url);
            registry.add("retouch.image-service.token", () -> ImageServiceProcess.TOKEN);
        };
    }
}
