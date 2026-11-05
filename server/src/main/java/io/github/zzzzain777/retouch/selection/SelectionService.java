package io.github.zzzzain777.retouch.selection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import io.github.zzzzain777.retouch.asset.Asset;
import io.github.zzzzain777.retouch.asset.AssetKind;
import io.github.zzzzain777.retouch.asset.AssetOut;
import io.github.zzzzain777.retouch.asset.AssetService;
import io.github.zzzzain777.retouch.asset.AssetSource;
import io.github.zzzzain777.retouch.asset.ImageFormat;
import io.github.zzzzain777.retouch.asset.ImageMeta;
import io.github.zzzzain777.retouch.common.ApiException;
import io.github.zzzzain777.retouch.selection.SelectionStore.Marker;
import io.github.zzzzain777.retouch.selection.SelectionStore.Stored;
import io.github.zzzzain777.retouch.session.EditSession;
import io.github.zzzzain777.retouch.session.LayerDocument;
import io.github.zzzzain777.retouch.session.SessionService;
import io.github.zzzzain777.retouch.tool.ImageOutput;
import io.github.zzzzain777.retouch.tool.ImageServiceClient;
import io.github.zzzzain777.retouch.tool.ToolError;

/**
 * 选区：笔刷涂抹（与已有选区合并）或点选（可追加标点），遮罩由图像服务栅格化后登记为 mask 素材。
 * 选区只限定区域，不限定图层；绑定画布修订号，画布一改就失效。
 */
@Service
public class SelectionService {

    static final String STALE = "画布已更新，请重新选择";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public record Point(double x, double y) {
    }

    /** 与上游 SelectIn 一致：points 与 strokes 二选一，radius 为笔刷宽度占画布短边的比例。 */
    public record SelectIn(Integer revision, List<Point> points, List<List<Point>> strokes, Double radius,
            Boolean append) {
    }

    public record SelectionOut(int revision, AssetOut mask, List<Marker> markers) {
    }

    private final SessionService sessions;
    private final SelectionStore store;
    private final AssetService assets;
    private final ImageServiceClient images;

    public SelectionService(SessionService sessions, SelectionStore store, AssetService assets,
            ImageServiceClient images) {
        this.sessions = sessions;
        this.store = store;
        this.assets = assets;
        this.images = images;
    }

    public SelectionOut select(UUID userId, UUID sessionId, SelectIn in) {
        EditSession record = sessions.get(userId, sessionId);
        if (in.revision() == null) {
            throw ApiException.unprocessable("revision：不能为空");
        }
        if (in.revision() != record.getRevision()) {
            throw ApiException.conflict(STALE);
        }
        double radius = in.radius() == null ? 0.03 : in.radius();
        if (radius < 0.005 || radius > 0.12) {
            throw ApiException.unprocessable("radius：取值需在 0.005 到 0.12 之间");
        }
        List<Point> points = in.points() == null ? List.of() : in.points();
        List<List<Point>> strokes = in.strokes() == null ? List.of()
                : in.strokes().stream().filter(stroke -> stroke != null && !stroke.isEmpty()).toList();
        for (Point point : points) {
            checkPoint(point);
        }
        strokes.forEach(stroke -> stroke.forEach(SelectionService::checkPoint));

        Optional<Stored> current = store.get(sessionId, record.getRevision());
        LayerDocument document = sessions.document(record);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("width", document.width());
        body.put("height", document.height());
        List<Marker> markers;
        if (!points.isEmpty()) {
            markers = new ArrayList<>(Boolean.TRUE.equals(in.append()) ? current.map(Stored::markers).orElse(List.of())
                    : List.of());
            for (Point point : points) {
                markers.add(new Marker(markers.size() + 1, point.x(), point.y()));
            }
            body.put("points", markers.stream().map(m -> Map.of("x", m.x(), "y", m.y())).toList());
        } else if (!strokes.isEmpty()) {
            markers = current.map(Stored::markers).orElse(List.of());
            body.put("strokes", strokes.stream()
                    .map(stroke -> stroke.stream().map(p -> Map.of("x", p.x(), "y", p.y())).toList()).toList());
            body.put("radius", radius);
            current.flatMap(stored -> assets.find(userId, stored.maskAssetId()))
                    .ifPresent(base -> body.put("base_key", base.getStorageKey()));
        } else {
            throw ApiException.unprocessable("请点选或涂抹选区");
        }

        Asset mask = rasterize(userId, body);
        var stored = new Stored(record.getRevision(), mask.getId(), markers);
        store.save(sessionId, stored);
        return new SelectionOut(stored.revision(), assets.out(mask), stored.markers());
    }

    public Optional<SelectionOut> current(UUID userId, UUID sessionId) {
        EditSession record = sessions.get(userId, sessionId);
        return store.get(sessionId, record.getRevision()).flatMap(stored -> assets.find(userId, stored.maskAssetId())
                .map(mask -> new SelectionOut(stored.revision(), assets.out(mask), stored.markers())));
    }

    public void clear(UUID userId, UUID sessionId) {
        sessions.get(userId, sessionId);
        store.clear(sessionId);
    }

    private Asset rasterize(UUID userId, Map<String, Object> body) {
        UUID opId = UUID.randomUUID();
        String prefix = ImageServiceClient.outputPrefix(opId, 1);
        body.put("output_prefix", prefix);
        List<ImageOutput> outputs;
        try {
            outputs = images.call("mask", opId, 1, body, TIMEOUT);
        } catch (ToolError failure) {
            if ("选区为空".equals(failure.getMessage())) {
                throw ApiException.unprocessable("选区为空");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, failure.getMessage());
        }
        ImageOutput output = outputs.getFirst();
        var meta = new ImageMeta(ImageFormat.valueOf(output.imageFormat()), output.width(), output.height(),
                output.sizeBytes(), output.hasAlpha());
        return assets.register(userId, output.key(), prefix, meta, output.sha256(), AssetKind.MASK, AssetSource.TOOL);
    }

    private static void checkPoint(Point point) {
        if (point == null || point.x() < 0 || point.x() > 1 || point.y() < 0 || point.y() > 1) {
            throw ApiException.unprocessable("坐标：取值需在 0 到 1 之间");
        }
    }
}
