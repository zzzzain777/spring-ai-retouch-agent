package io.github.zzzzain777.retouch.tool;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("retouch.worker")
public record WorkerProperties(boolean enabled, int concurrency, Duration pollInterval, Duration recoverInterval) {
}
