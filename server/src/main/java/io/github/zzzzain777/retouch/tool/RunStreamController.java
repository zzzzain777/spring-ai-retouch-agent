package io.github.zzzzain777.retouch.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.common.ApiException;

/**
 * 单个任务的进度流（与上游一致挂在 /events 下，便于反向代理关闭缓冲）。
 * 先订阅 Redis 频道再读库里的快照并发出，之后转发事件，到终态或 600 秒断开；空闲时每 15 秒发心跳。
 * 另外每 3 秒对一次库：Redis 丢消息或订阅尚未生效时，终态也一定能送达。
 */
@RestController
class RunStreamController {

    private static final Logger log = LoggerFactory.getLogger(RunStreamController.class);
    private static final Duration MAX_STREAM = Duration.ofSeconds(600);
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);
    private static final Duration RECHECK = Duration.ofSeconds(3);

    private final ToolRunRepository runs;
    private final RunEvents events;
    private final RedisMessageListenerContainer container;
    private final JsonMapper json;
    private final ScheduledExecutorService ticker = Executors.newScheduledThreadPool(1,
            Thread.ofVirtual().name("sse-ticker").factory());

    RunStreamController(ToolRunRepository runs, RunEvents events, RedisMessageListenerContainer container,
            JsonMapper json) {
        this.runs = runs;
        this.events = events;
        this.container = container;
        this.json = json;
    }

    @GetMapping("/events/runs/{runId}")
    ResponseEntity<ResponseBodyEmitter> stream(CurrentUser user, @PathVariable UUID runId) {
        runs.findByIdAndUserId(runId, user.id()).orElseThrow(() -> ApiException.notFound("任务不存在"));
        var emitter = new ResponseBodyEmitter(MAX_STREAM.plusSeconds(10).toMillis());
        var stream = new Stream(emitter, runId, user.id());
        stream.open();
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no").body(emitter);
    }

    private final class Stream {

        private final ResponseBodyEmitter emitter;
        private final UUID runId;
        private final UUID userId;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Instant deadline = Instant.now().plus(MAX_STREAM);
        private final ChannelTopic topic;
        private final MessageListener listener;
        private volatile Instant lastSent = Instant.now();
        private ScheduledFuture<?> tick;

        Stream(ResponseBodyEmitter emitter, UUID runId, UUID userId) {
            this.emitter = emitter;
            this.runId = runId;
            this.userId = userId;
            this.topic = new ChannelTopic(RunEvents.channel(runId));
            this.listener = (message, pattern) -> forward(new String(message.getBody(), StandardCharsets.UTF_8));
        }

        void open() {
            emitter.onCompletion(this::cleanup);
            emitter.onTimeout(this::close);
            emitter.onError(failure -> close());
            try {
                container.addMessageListener(listener, topic);
            } catch (RuntimeException redisDown) {
                log.warn("SSE 订阅 Redis 失败，退回为只按库轮询 run_id={}", runId);
            }
            sendSnapshot();
            if (!closed.get()) {
                tick = ticker.scheduleWithFixedDelay(this::tick, RECHECK.toMillis(), RECHECK.toMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }

        private void sendSnapshot() {
            runs.findByIdAndUserId(runId, userId).ifPresentOrElse(run -> {
                send(json.writeValueAsString(events.snapshot(run)));
                if (run.getStatus().terminal()) {
                    close();
                }
            }, this::close);
        }

        private void forward(String payload) {
            send(payload);
            try {
                if (RunStatus.valueOf(json.readTree(payload).path("status").asString("").toUpperCase()).terminal()) {
                    close();
                }
            } catch (IllegalArgumentException unknown) {
                // 非法帧忽略
            }
        }

        private void tick() {
            if (closed.get()) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                close();
                return;
            }
            var run = runs.findByIdAndUserId(runId, userId);
            if (run.isEmpty() || run.get().getStatus().terminal()) {
                sendSnapshot();
                return;
            }
            if (Duration.between(lastSent, Instant.now()).compareTo(HEARTBEAT) >= 0) {
                write(": ping\n\n");
            }
        }

        private void send(String payload) {
            write("data: " + payload + "\n\n");
        }

        private synchronized void write(String text) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(text, MediaType.TEXT_PLAIN);
                lastSent = Instant.now();
            } catch (IOException | IllegalStateException gone) {
                close();
            }
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                cleanup();
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // 连接已断开
                }
            }
        }

        private void cleanup() {
            closed.set(true);
            if (tick != null) {
                tick.cancel(false);
            }
            try {
                container.removeMessageListener(listener, topic);
            } catch (RuntimeException ignored) {
                // Redis 不可用时没有可移除的订阅
            }
        }
    }
}
