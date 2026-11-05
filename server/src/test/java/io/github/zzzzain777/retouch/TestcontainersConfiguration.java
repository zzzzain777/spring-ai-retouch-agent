package io.github.zzzzain777.retouch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试用真实的 PostgreSQL、Redis 和 S3 兼容存储（moto），不用内存替身。
 * 镜像名用 Docker Hub 官方名；拉取受限的环境可在 ~/.testcontainers.properties 设置 hub.image.name.prefix。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }

    @Bean
    GenericContainer<?> s3() {
        return new GenericContainer<>(DockerImageName.parse("motoserver/moto:5.1.4")).withExposedPorts(5000);
    }

    @Bean
    DynamicPropertyRegistrar s3Properties(GenericContainer<?> s3) {
        return registry -> registry.add("retouch.storage.endpoint",
                () -> "http://" + s3.getHost() + ":" + s3.getMappedPort(5000));
    }
}
