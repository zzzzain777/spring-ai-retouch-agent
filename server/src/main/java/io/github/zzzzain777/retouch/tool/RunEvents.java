package io.github.zzzzain777.retouch.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 任务进度事件：每次状态写库提交后发布到 Redis 频道 run:{id}。只用于推送，SSE 连接先读库里的快照，
 * 所以 Redis 丢消息或不可用时不会丢状态，只是少了实时帧。
 */
@Component
public class RunEvents {

    private static final Logger log = LoggerFactory.getLogger(RunEvents.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RunEvents(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public static String channel(java.util.UUID runId) {
        return "run:" + runId;
    }

    /** 与上游 SSE 帧一致：{id, tool, status, progress, stage, error, result}。 */
    public Map<String, Object> snapshot(ToolRun run) {
        var frame = new LinkedHashMap<String, Object>();
        frame.put("id", run.getId().toString());
        frame.put("tool", run.getTool());
        frame.put("status", run.getStatus().value());
        frame.put("progress", run.getProgress());
        frame.put("stage", run.getStage());
        frame.put("error", run.getError());
        frame.put("result", json.readTree(run.getResult()));
        return frame;
    }

    public void publish(ToolRun run) {
        try {
            redis.convertAndSend(channel(run.getId()), json.writeValueAsString(snapshot(run)));
        } catch (RuntimeException unavailable) {
            log.warn("进度事件发布失败 run_id={}：{}", run.getId(), unavailable.getClass().getSimpleName());
        }
    }
}
