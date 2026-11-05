package io.github.zzzzain777.retouch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** 把图像模型地址指向本地假服务，并给一个测试用的密钥。 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeDashScopeConfiguration {

    @Bean(destroyMethod = "close")
    FakeDashScopeServer fakeDashScope() throws Exception {
        return new FakeDashScopeServer();
    }

    @Bean
    DynamicPropertyRegistrar fakeDashScopeProperties(FakeDashScopeServer server) {
        return registry -> {
            registry.add("retouch.image.dashscope.base-url", server::url);
            registry.add("retouch.image.dashscope.api-key", () -> FakeDashScopeServer.KEY);
        };
    }
}
