package io.github.zzzzain777.retouch.selection;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 选区存在 Redis {@code selection:{sessionId}}，24 小时过期，绑定画布修订号：修订号对不上就当作没有选区。
 * 遮罩字节本身是 mask 类素材，这里只存它的 id 与点选标记。Redis 不可用时按无选区处理。
 */
@Component
public class SelectionStore {

    private static final Logger log = LoggerFactory.getLogger(SelectionStore.class);
    static final Duration TTL = Duration.ofHours(24);

    public record Marker(int index, double x, double y) {
    }

    public record Stored(int revision, UUID maskAssetId, List<Marker> markers) {

        public Stored {
            markers = markers == null ? List.of() : List.copyOf(markers);
        }
    }

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public SelectionStore(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    static String key(UUID sessionId) {
        return "selection:" + sessionId;
    }

    /** 当前修订号下的选区。 */
    public Optional<Stored> get(UUID sessionId, int revision) {
        String raw;
        try {
            raw = redis.opsForValue().get(key(sessionId));
        } catch (RuntimeException redisDown) {
            log.warn("读取选区失败，按无选区处理 session_id={}：{}", sessionId, redisDown.getMessage());
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.empty();
        }
        Stored stored = json.readValue(raw, Stored.class);
        return stored.revision() == revision ? Optional.of(stored) : Optional.empty();
    }

    public void save(UUID sessionId, Stored stored) {
        redis.opsForValue().set(key(sessionId), json.writeValueAsString(stored), TTL);
    }

    public void clear(UUID sessionId) {
        redis.delete(key(sessionId));
    }
}
