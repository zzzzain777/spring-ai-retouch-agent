package io.github.zzzzain777.retouch.tool;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.asset.Asset;
import io.github.zzzzain777.retouch.asset.AssetService;
import io.github.zzzzain777.retouch.asset.AssetSource;
import io.github.zzzzain777.retouch.asset.ImageFormat;
import io.github.zzzzain777.retouch.asset.ImageMeta;
import io.github.zzzzain777.retouch.session.EditSession;
import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.LayerDocument.Layer;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerKind;
import io.github.zzzzain777.retouch.session.LayerDocument.LayerMissing;
import io.github.zzzzain777.retouch.session.SessionService;
import io.github.zzzzain777.retouch.session.SessionService.Edit;

/**
 * 队列 Worker：队列就是 tool_runs 表。领取用 FOR UPDATE SKIP LOCKED 并写租约；执行时不持有数据库事务；
 * 完成时锁住任务行，只有“仍在运行且 attempt 没变”才写结果，迟到的旧结果直接丢弃。
 * 租约过期的任务（进程崩溃或重启）由恢复扫描处理：幂等且未超次数的放回队列，否则记为“执行中断”。
 */
@Component
public class RunWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);

    /** 执行中发现取消请求时用来跳出处理流程。 */
    static final class Canceled extends RuntimeException {
        Canceled() {
            super("任务已取消", null, false, false);
        }
    }

    private record Claimed(UUID id, int attempt) {
    }

    private final WorkerProperties props;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ToolRunRepository runs;
    private final ToolRegistry registry;
    private final ToolParams params;
    private final ImageServiceClient images;
    private final AssetService assets;
    private final SessionService sessions;
    private final RunEvents events;
    private final JsonMapper json;
    private final List<RunListener> listeners;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService executor;

    public RunWorker(WorkerProperties props, JdbcClient jdbc, TransactionTemplate tx, ToolRunRepository runs,
            ToolRegistry registry, ToolParams params, ImageServiceClient images, AssetService assets,
            SessionService sessions, RunEvents events, JsonMapper json, List<RunListener> listeners) {
        this.props = props;
        this.jdbc = jdbc;
        this.tx = tx;
        this.runs = runs;
        this.registry = registry;
        this.params = params;
        this.images = images;
        this.assets = assets;
        this.sessions = sessions;
        this.events = events;
        this.json = json;
        this.listeners = listeners;
    }

    @Override
    public void start() {
        if (!props.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < props.concurrency(); i++) {
            executor.submit(this::pollLoop);
        }
        executor.submit(this::recoverLoop);
        log.info("任务 Worker 已启动：并发 {}，worker_id={}", props.concurrency(), workerId);
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                Optional<Claimed> claimed = claim();
                if (claimed.isPresent()) {
                    process(claimed.get());
                } else {
                    Thread.sleep(props.pollInterval());
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException unexpected) {
                log.error("Worker 循环异常", unexpected);
                sleepQuietly(props.pollInterval());
            }
        }
    }

    private void recoverLoop() {
        while (running.get()) {
            try {
                recoverExpired();
                Thread.sleep(props.recoverInterval());
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException unexpected) {
                log.error("租约恢复扫描异常", unexpected);
                sleepQuietly(props.recoverInterval());
            }
        }
    }

    /** 领取最早排队的任务：状态改为 running、attempt 加一、写入临时租约与 worker_id。 */
    Optional<Claimed> claim() {
        return tx.execute(status -> jdbc.sql("""
                update tool_runs set status = 'running', attempt = attempt + 1,
                       started_at = coalesce(started_at, now()), progress = greatest(progress, 5), stage = '已开始',
                       lease_until = now() + interval '15 minutes', worker_id = :worker, updated_at = now()
                 where id = (select id from tool_runs where status = 'queued'
                              order by created_at for update skip locked limit 1)
                returning id, attempt
                """).param("worker", workerId).query((rs, n) -> new Claimed(rs.getObject("id", UUID.class),
                rs.getInt("attempt"))).optional());
    }

    private void process(Claimed claimed) {
        ToolRun run = runs.findById(claimed.id()).orElseThrow();
        Optional<ToolSpec<?>> spec = registry.find(run.getTool());
        if (spec.isEmpty() || spec.get().imageHandler() == null) {
            finish(claimed, "未注册的工具：" + run.getTool(), false);
            return;
        }
        extendLease(claimed, spec.get().timeout().plus(LEASE_MARGIN));
        events.publish(run);
        execute(spec.get(), run, claimed);
    }

    private <P> void execute(ToolSpec<P> spec, ToolRun run, Claimed claimed) {
        try {
            P value = params.read(spec, run.getParams());
            var context = new Context(run, claimed, spec);
            ImageOutcome outcome = spec.imageHandler().run(context, value);
            complete(spec, claimed, outcome);
        } catch (Canceled canceled) {
            finish(claimed, null, true);
        } catch (ToolError | LayerMissing failure) {
            finish(claimed, failure.getMessage(), false);
        } catch (RuntimeException unexpected) {
            log.error("工具执行异常 tool={} run_id={}", spec.name(), claimed.id(), unexpected);
            finish(claimed, "执行失败，请重试", false);
        }
    }

    /** 完成事务：只接受“仍在运行且 attempt 没变”的结果；登记素材、锁会话、写编辑与历史、任务成功。 */
    private void complete(ToolSpec<?> spec, Claimed claimed, ImageOutcome outcome) {
        ToolRun done;
        try {
            done = tx.execute(status -> {
                ToolRun run = runs.lockById(claimed.id()).orElseThrow();
                if (!current(run, claimed)) {
                    log.warn("丢弃迟到的结果 run_id={} attempt={}", claimed.id(), claimed.attempt());
                    return null;
                }
                if (run.isCancelRequested()) {
                    run.cancel();
                    return run;
                }
                Map<String, Object> result = apply(run, claimed, spec, outcome);
                run.succeed(json.writeValueAsString(result));
                return run;
            });
        } catch (ToolError | LayerMissing failure) {
            finish(claimed, failure.getMessage(), false);
            return;
        }
        if (done != null) {
            afterFinish(done);
        }
    }

    private Map<String, Object> apply(ToolRun run, Claimed claimed, ToolSpec<?> spec, ImageOutcome outcome) {
        String prefix = ImageServiceClient.outputPrefix(run.getId(), claimed.attempt());
        return switch (outcome) {
            case ImageOutcome.ReplaceLayerImage replace -> {
                Asset asset = register(run, prefix, replace.output(), replace.kind());
                EditSession record = sessions.lock(run.getUserId(), run.getSessionId());
                LayerDocument document = sessions.document(record);
                Layer layer = document.layers().stream().filter(l -> l.id().equals(replace.layerId())).findFirst()
                        .orElseThrow(() -> new ToolError("目标图层已不存在"));
                if (layer.kind() != LayerKind.IMAGE) {
                    throw new ToolError("只能对图像图层做此操作");
                }
                LayerDocument next = document.replace(layer.withAssetId(asset.getId()));
                boolean single = next.layers().stream().filter(l -> l.kind() == LayerKind.IMAGE).count() <= 1;
                var result = new LinkedHashMap<String, Object>();
                result.put("document", json.convertValue(next, Map.class));
                result.put("asset_ids", List.of(asset.getId().toString()));
                if (single) {
                    result.put("adopt_asset_id", asset.getId().toString());
                }
                Map<String, Object> stored = json.readValue(run.getParams(), Map.class);
                sessions.apply(record, spec.name(),
                        new Edit(stored, next, single ? asset : null, List.of(asset), result, true));
                yield result;
            }
        };
    }

    private Asset register(ToolRun run, String prefix, ImageOutput output, io.github.zzzzain777.retouch.asset.AssetKind kind) {
        ImageFormat format;
        try {
            format = ImageFormat.valueOf(output.imageFormat());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ToolError("图像服务返回了不支持的格式");
        }
        var meta = new ImageMeta(format, output.width(), output.height(), output.sizeBytes(), output.hasAlpha());
        try {
            return assets.register(run.getUserId(), output.key(), prefix, meta, output.sha256(), kind,
                    AssetSource.TOOL);
        } catch (IllegalStateException foreignKey) {
            throw new ToolError("图像服务返回的结果位置不合法");
        }
    }

    /** 失败或取消：同样只对“仍在运行且 attempt 没变”的任务生效。 */
    private void finish(Claimed claimed, String error, boolean canceled) {
        ToolRun done = tx.execute(status -> {
            ToolRun run = runs.lockById(claimed.id()).orElseThrow();
            if (!current(run, claimed)) {
                return null;
            }
            if (canceled) {
                run.cancel();
            } else {
                run.fail(error);
            }
            return run;
        });
        if (done != null) {
            afterFinish(done);
        }
    }

    private void afterFinish(ToolRun run) {
        events.publish(run);
        for (RunListener listener : listeners) {
            try {
                listener.finished(run);
            } catch (RuntimeException failure) {
                log.error("任务结束回调失败 run_id={}", run.getId(), failure);
            }
        }
    }

    private static boolean current(ToolRun run, Claimed claimed) {
        return run.getStatus() == RunStatus.RUNNING && run.getAttempt() == claimed.attempt();
    }

    private void extendLease(Claimed claimed, Duration lease) {
        jdbc.sql("update tool_runs set lease_until = now() + make_interval(secs => :secs) "
                + "where id = :id and status = 'running' and attempt = :attempt")
                .param("secs", lease.toSeconds()).param("id", claimed.id()).param("attempt", claimed.attempt())
                .update();
    }

    /** 租约过期的 running 任务：幂等且未超过次数的放回队列，否则记为失败。启动后立即执行一次。 */
    public void recoverExpired() {
        List<ToolRun> touched = tx.execute(status -> {
            List<UUID> expired = jdbc.sql("select id from tool_runs where status = 'running' and lease_until < now() "
                    + "for update skip locked").query(UUID.class).list();
            return expired.stream().map(id -> {
                ToolRun run = runs.findById(id).orElseThrow();
                boolean retry = registry.find(run.getTool()).map(ToolSpec::idempotent).orElse(false)
                        && run.getAttempt() < run.getMaxAttempts() && !run.isCancelRequested();
                if (retry) {
                    run.requeue();
                } else if (run.isCancelRequested()) {
                    run.cancel();
                } else {
                    run.fail("执行中断，请重试");
                }
                log.warn("租约过期 run_id={} tool={} attempt={} → {}", id, run.getTool(), run.getAttempt(),
                        run.getStatus().value());
                return run;
            }).toList();
        });
        for (ToolRun run : touched) {
            if (run.getStatus().terminal()) {
                afterFinish(run);
            } else {
                events.publish(run);
            }
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    /** 给图像工具的执行上下文；所有读取都按任务所属用户过滤。 */
    private final class Context implements ImageContext {

        private final ToolRun run;
        private final Claimed claimed;
        private final ToolSpec<?> spec;
        private LayerDocument document;

        Context(ToolRun run, Claimed claimed, ToolSpec<?> spec) {
            this.run = run;
            this.claimed = claimed;
            this.spec = spec;
        }

        @Override
        public UUID runId() {
            return run.getId();
        }

        @Override
        public int attempt() {
            return claimed.attempt();
        }

        @Override
        public LayerDocument document() {
            if (document == null) {
                if (run.getSessionId() == null) {
                    throw new ToolError("此工具需要在编辑会话中使用");
                }
                document = sessions.document(sessions.get(run.getUserId(), run.getSessionId()));
            }
            return document;
        }

        @Override
        public Layer target(String layerId) {
            return document().resolve(layerId);
        }

        @Override
        public String layerImageKey(Layer layer) {
            if (layer.kind() != LayerKind.IMAGE || layer.assetId() == null) {
                throw new ToolError("该图层没有可处理的图像");
            }
            return assets.find(run.getUserId(), layer.assetId()).map(Asset::getStorageKey)
                    .orElseThrow(() -> new ToolError("图层素材不存在"));
        }

        @Override
        public void report(int progress, String stage) {
            ToolRun updated = tx.execute(status -> {
                ToolRun locked = runs.lockById(run.getId()).orElseThrow();
                if (!current(locked, claimed)) {
                    throw new Canceled();
                }
                if (locked.isCancelRequested()) {
                    throw new Canceled();
                }
                locked.report(progress, stage);
                return locked;
            });
            events.publish(updated);
        }

        @Override
        public List<ImageOutput> call(String op, Map<String, Object> body) {
            var request = new LinkedHashMap<>(body);
            request.put("output_prefix", ImageServiceClient.outputPrefix(run.getId(), claimed.attempt()));
            return images.call(op, run.getId(), claimed.attempt(), request, spec.timeout());
        }
    }
}
