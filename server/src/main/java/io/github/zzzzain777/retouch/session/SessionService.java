package io.github.zzzzain777.retouch.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.zzzzain777.retouch.asset.Asset;
import io.github.zzzzain777.retouch.common.ApiException;

/**
 * 会话、画布文档与线性历史。所有改动会话的方法都先锁住会话行，再按“截断重做分支 → 写新快照”的顺序落库。
 * 语义与上游一致：历史最多保留 20 条；撤销与重做在 params.before 与 result 两份快照之间移动 history_seq，
 * 并恢复当时的修订号。
 */
@Service
public class SessionService {

    public static final int HISTORY_LIMIT = 20;
    public static final int TITLE_LIMIT = 80;
    public static final String DEFAULT_TITLE = "未命名会话";
    static final String NOT_FOUND = "会话不存在";

    private final EditSessionRepository sessions;
    private final EditHistoryRepository history;
    private final SessionWall wall;
    private final JsonMapper json;

    public SessionService(EditSessionRepository sessions, EditHistoryRepository history, SessionWall wall,
            JsonMapper json) {
        this.sessions = sessions;
        this.history = history;
        this.wall = wall;
        this.json = json;
    }

    /** 一次可撤销编辑的内容。document 与 current 都为空、也没有新素材进图片墙时，不产生历史。 */
    public record Edit(Map<String, Object> params, LayerDocument document, Asset current, List<Asset> extraAssets,
            Map<String, Object> result, boolean bumpRevision) {

        public static Edit document(LayerDocument document, Map<String, Object> params) {
            return new Edit(params, document, null, List.of(), Map.of(), true);
        }
    }

    @Transactional
    public EditSession create(UUID userId, Asset current, List<Asset> extra, String title) {
        var record = sessions.saveAndFlush(new EditSession(UUID.randomUUID(), userId, normalizeTitle(title),
                current.getId(), write(LayerDocument.of(current))));
        var onWall = new ArrayList<UUID>();
        onWall.add(current.getId());
        extra.forEach(asset -> onWall.add(asset.getId()));
        wall.attach(record.getId(), onWall);

        ObjectNode result = snapshot(record);
        result.put("asset_id", current.getId().toString());
        appendHistory(record, "create_session", json.createObjectNode(), result);
        return sessions.saveAndFlush(record);
    }

    public EditSession get(UUID userId, UUID sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    /** 读取并锁住会话行，供所有写操作使用；必须在事务内调用。 */
    public EditSession lock(UUID userId, UUID sessionId) {
        return sessions.lockByIdAndUserId(sessionId, userId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    public List<EditSession> list(UUID userId, int limit) {
        return sessions.findByUserIdOrderByUpdatedAtDesc(userId, Limit.of(limit));
    }

    public LayerDocument document(EditSession record) {
        return json.readValue(record.getDocument(), LayerDocument.class);
    }

    /** 应用一次可撤销编辑：先截断重做分支，再写入编辑后的快照。调用方须已持有会话行锁。 */
    @Transactional
    public EditSession apply(EditSession record, String action, Edit edit) {
        ObjectNode before = snapshot(record);
        UUID nextCurrent = record.getCurrentAssetId();
        String nextDocument = record.getDocument();
        boolean changed = false;

        LayerDocument document = edit.document();
        if (edit.current() != null && !edit.current().getId().equals(record.getCurrentAssetId())) {
            nextCurrent = edit.current().getId();
            if (document == null) {
                document = LayerDocument.of(edit.current());
            }
            changed = true;
        }
        if (document != null && !document.equals(document(record))) {
            nextDocument = write(document);
            changed = true;
        }
        // 结果只进图片墙时画布没变，但仍记一条历史，用户才能在编辑记录里找到这次产出
        int attached = wall.attach(record.getId(), edit.extraAssets().stream().map(Asset::getId).toList());
        if (!changed && attached == 0) {
            return record;
        }

        history.deleteAfter(record.getId(), record.getHistorySeq());
        record.setCurrentAssetId(nextCurrent);
        record.setDocument(nextDocument);
        if (edit.bumpRevision()) {
            record.setRevision(record.getRevision() + 1);
        }

        ObjectNode params = json.valueToTree(edit.params() == null ? Map.of() : edit.params());
        params.set("before", before);
        ObjectNode result = json.valueToTree(edit.result() == null ? Map.of() : edit.result());
        result.setAll(snapshot(record));
        appendHistory(record, action, params, result);
        return sessions.saveAndFlush(record);
    }

    @Transactional
    public EditSession rename(EditSession record, String title) {
        record.setTitle(normalizeTitle(title));
        return sessions.saveAndFlush(record);
    }

    /** 切换画布当前图：回到这张图上次在画布上的文档；修订号递增，使旧选区失效。 */
    @Transactional
    public EditSession switchCurrent(EditSession record, Asset asset) {
        LayerDocument document = lastDocumentFor(record, asset.getId());
        if (croppedBase(document, asset)) {
            document = LayerDocument.of(asset);
        }
        if (record.getCurrentAssetId().equals(asset.getId())) {
            // 裁剪不换素材：点自己等于回到原图画幅；没裁过就什么都不做
            if (!croppedBase(document(record), asset)) {
                return record;
            }
            document = LayerDocument.of(asset);
        }
        return apply(record, "switch_current", new Edit(Map.of(), document, asset, List.of(asset), Map.of(), true));
    }

    @Transactional
    public EditSession undo(EditSession record) {
        JsonNode before = record.getHistorySeq() <= 1 ? null
                : history.findBySessionIdAndSeq(record.getId(), record.getHistorySeq())
                        .map(entry -> read(entry.getParams()).get("before")).orElse(null);
        if (before == null || !before.isObject()) {
            throw ApiException.conflict("没有可撤销的操作");
        }
        restore(record, before);
        record.setHistorySeq(record.getHistorySeq() - 1);
        return sessions.saveAndFlush(record);
    }

    @Transactional
    public EditSession redo(EditSession record) {
        JsonNode after = history.findBySessionIdAndSeq(record.getId(), record.getHistorySeq() + 1)
                .map(entry -> read(entry.getResult())).orElse(null);
        if (after == null || !after.has("document")) {
            throw ApiException.conflict("没有可重做的操作");
        }
        restore(record, after);
        record.setHistorySeq(record.getHistorySeq() + 1);
        return sessions.saveAndFlush(record);
    }

    public List<EditHistory> history(EditSession record) {
        return history.findBySessionIdOrderBySeqDesc(record.getId());
    }

    public boolean canUndo(EditSession record) {
        return record.getHistorySeq() > 1;
    }

    public boolean canRedo(EditSession record) {
        return history.maxSeq(record.getId()) > record.getHistorySeq();
    }

    /** 本次操作前的画布，供前端做前后对比。 */
    public LayerDocument previousDocument(EditSession record) {
        if (record.getHistorySeq() <= 1) {
            return null;
        }
        return history.findBySessionIdAndSeq(record.getId(), record.getHistorySeq())
                .map(entry -> read(entry.getParams()).path("before").get("document"))
                .filter(node -> node != null && node.isObject())
                .map(node -> json.treeToValue(node, LayerDocument.class))
                .orElse(null);
    }

    public List<UUID> wallAssetIds(EditSession record) {
        return wall.assetIds(record.getId());
    }

    public JsonNode read(String text) {
        return json.readTree(text);
    }

    static String normalizeTitle(String text) {
        String cleaned = String.join(" ", (text == null ? "" : text).strip().split("\\s+"));
        if (cleaned.codePointCount(0, cleaned.length()) > TITLE_LIMIT) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, TITLE_LIMIT));
        }
        return cleaned.isEmpty() ? DEFAULT_TITLE : cleaned;
    }

    private void appendHistory(EditSession record, String action, ObjectNode params, ObjectNode result) {
        int seq = record.getHistorySeq() + 1;
        history.save(new EditHistory(record.getUserId(), record.getId(), seq, action, params.toString(),
                result.toString()));
        history.deleteUpTo(record.getId(), seq - HISTORY_LIMIT);
        record.setHistorySeq(seq);
    }

    private ObjectNode snapshot(EditSession record) {
        ObjectNode node = json.createObjectNode();
        node.set("document", read(record.getDocument()));
        node.put("current_asset_id", record.getCurrentAssetId().toString());
        node.put("revision", record.getRevision());
        return node;
    }

    private void restore(EditSession record, JsonNode state) {
        record.setDocument(state.get("document").toString());
        record.setCurrentAssetId(UUID.fromString(state.get("current_asset_id").asString()));
        record.setRevision(state.get("revision").asInt());
    }

    /** 这张图上次在画布上时的文档；没有记录时返回 null，由 apply 以整图新建。 */
    private LayerDocument lastDocumentFor(EditSession record, UUID assetId) {
        String target = assetId.toString();
        for (EditHistory entry : history(record)) {
            LayerDocument found = documentFor(read(entry.getResult()), target);
            if (found == null) {
                found = documentFor(read(entry.getParams()).get("before"), target);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private LayerDocument documentFor(JsonNode state, String assetId) {
        if (state == null || !state.isObject() || !assetId.equals(state.path("current_asset_id").asString(null))) {
            return null;
        }
        JsonNode raw = state.get("document");
        return raw == null || !raw.isObject() ? null : json.treeToValue(raw, LayerDocument.class);
    }

    /** 裁剪只缩小画布、不换素材；这类文档下点回这张图，应当回到原生画幅。 */
    private static boolean croppedBase(LayerDocument document, Asset asset) {
        if (document == null || document.layers().size() != 1) {
            return false;
        }
        var layer = document.layers().getFirst();
        if (!LayerDocument.BASE_LAYER_ID.equals(layer.id()) || !asset.getId().equals(layer.assetId())) {
            return false;
        }
        return document.width() < asset.getWidth() || document.height() < asset.getHeight();
    }

    private String write(LayerDocument document) {
        return json.writeValueAsString(document);
    }

    static Map<String, Object> map(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
