package io.github.zzzzain777.retouch.session;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 会话图片墙（session_assets）：保留所有候选与产出，切换当前图不覆盖任何已有结果。 */
@Component
class SessionWall {

    private final JdbcClient jdbc;

    SessionWall(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 按顺序挂上尚未在墙上的素材，返回新挂上的数量。 */
    int attach(UUID sessionId, List<UUID> assetIds) {
        var known = new HashSet<>(assetIds(sessionId));
        int position = jdbc.sql("select coalesce(max(position), 0) from session_assets where session_id = ?")
                .param(sessionId).query(Integer.class).single();
        int added = 0;
        for (UUID assetId : assetIds) {
            if (known.add(assetId)) {
                position++;
                jdbc.sql("insert into session_assets (session_id, asset_id, position) values (?, ?, ?)")
                        .params(sessionId, assetId, position).update();
                added++;
            }
        }
        return added;
    }

    List<UUID> assetIds(UUID sessionId) {
        return jdbc.sql("select asset_id from session_assets where session_id = ? order by position")
                .param(sessionId).query(UUID.class).list();
    }

    boolean contains(UUID sessionId, UUID assetId) {
        return jdbc.sql("select count(*) from session_assets where session_id = ? and asset_id = ?")
                .params(sessionId, assetId).query(Integer.class).single() > 0;
    }
}
