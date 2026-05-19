package org.zendo.web.service;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionManager;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.zendo.protocol.t808.T0001;
import org.zendo.protocol.t808.T8803;
import org.zendo.protocol.t1078.T1206;
import org.zendo.protocol.t1078.T9206;
import org.zendo.web.config.JTProperties;
import org.zendo.web.endpoint.MessageManager;
import org.zendo.web.model.entity.DeviceFileRequest;
import org.zendo.web.repository.DeviceFileRequestRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Watches the {@code device_file_requests} collection for newly-inserted
 * documents and dispatches the appropriate JT808 command when the target
 * device is currently connected to this server instance.
 *
 * <p>
 * Only {@code insert} events are handled — updates/replaces are ignored so
 * that completed requests are never re-sent.
 * </p>
 *
 * <p>
 * Supported types and the commands they map to:
 * <ul>
 * <li>{@code IMAGE} — T8803 (存储多媒体数据上传, type=0)</li>
 * <li>{@code VIDEO} — T8803 (存储多媒体数据上传, type=2)</li>
 * <li>{@code LOG} — TODO: implement when log-upload protocol class is
 * available</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class DeviceFileRequestWatchService implements SmartLifecycle {

    private static final String COLLECTION = "devicefilerequests";
    private static final long RECONNECT_DELAY_MS = 5_000;

    private final MongoTemplate mongoTemplate;
    private final SessionManager sessionManager;
    private final MessageManager messageManager;
    private final JTProperties jtProperties;
    private final DeviceFileRequestRepository fileRequestRepository;

    @Autowired
    public DeviceFileRequestWatchService(
            MongoTemplate mongoTemplate,
            @Lazy SessionManager sessionManager,
            @Lazy MessageManager messageManager,
            JTProperties jtProperties,
            DeviceFileRequestRepository fileRequestRepository) {
        this.mongoTemplate = mongoTemplate;
        this.sessionManager = sessionManager;
        this.messageManager = messageManager;
        this.jtProperties = jtProperties;
        this.fileRequestRepository = fileRequestRepository;
    }

    private volatile boolean running = false;
    private volatile MongoCursor<?> activeCursor;
    private Thread watchThread;

    // ── SmartLifecycle ────────────────────────────────────────────────────────

    @Override
    public void start() {
        running = true;
        watchThread = new Thread(this::watchLoop, "device-file-request-watch");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("DeviceFileRequestWatchService started — listening on {}", COLLECTION);
    }

    @Override
    public void stop() {
        running = false;
        MongoCursor<?> cursor = activeCursor;
        if (cursor != null) {
            try {
                cursor.close();
            } catch (Exception ignored) {
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
        log.info("DeviceFileRequestWatchService stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ── Change stream loop ────────────────────────────────────────────────────

    private void watchLoop() {
        while (running) {
            try {
                openAndDrain();
            } catch (Exception e) {
                if (running) {
                    log.error("Change stream error on '{}', reconnecting in {}ms", COLLECTION, RECONNECT_DELAY_MS, e);
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void openAndDrain() {
        List<Bson> pipeline = List.of(
                Aggregates.match(Filters.eq("operationType", "insert")));

        try (MongoCursor<ChangeStreamDocument<Document>> cursor = mongoTemplate.getCollection(COLLECTION)
                .watch(pipeline)
                .fullDocument(FullDocument.UPDATE_LOOKUP)
                .iterator()) {

            activeCursor = cursor;
            log.info("Change stream cursor opened on '{}'", COLLECTION);

            while (running && cursor.hasNext()) {
                ChangeStreamDocument<Document> event = cursor.next();
                Document fullDoc = event.getFullDocument();
                if (fullDoc != null) {
                    DeviceFileRequest request = mongoTemplate.getConverter()
                            .read(DeviceFileRequest.class, fullDoc);
                    log.info("Change stream insert received: id={} cid={} type={} status={}",
                            request.getId(), request.getCid(), request.getType(), request.getStatus());
                    handleRequest(request);
                }
            }
        } finally {
            activeCursor = null;
        }
    }

    // ── Business logic ────────────────────────────────────────────────────────

    /**
     * Called when a device reconnects. Fetches all PENDING file requests for the
     * given {@code cid} and dispatches them exactly as the change-stream watch
     * would for a fresh insert.
     */
    public void processPendingRequests(String cid) {
        List<DeviceFileRequest> pending = fileRequestRepository.findByCidAndStatus(cid,
                DeviceFileRequest.Status.PENDING);
        if (pending.isEmpty())
            return;
        log.info("Device cid={} reconnected — processing {} pending file request(s)", cid, pending.size());
        pending.forEach(this::handleRequest);
    }

    private void handleRequest(DeviceFileRequest request) {
        String cid = request.getCid();
        if (cid == null) {
            log.warn("DeviceFileRequest {} has null cid — skipping", request.getId());
            return;
        }

        // Only handle devices whose TCP session is on this server instance
        Session deviceSession = sessionManager.get(cid);
        if (deviceSession == null) {
            log.warn("Device cid={} is not connected to this instance — ignoring file request {}", cid,
                    request.getId());
            return;
        }

        if (request.getStatus() != DeviceFileRequest.Status.PENDING) {
            log.warn("File request {} is not pending (status={}) — skipping", request.getId(), request.getStatus());
            return;
        }

        log.info("Dispatching file request id={} type={} cid={}", request.getId(), request.getType(), cid);

        switch (request.getType()) {
            case IMAGE -> handleImageRequest(request);
            case VIDEO -> handleVideoRequest(request);
            case LOG -> handleLogRequest(request);
            default -> markFailed(request.getId(), "Unsupported request type: " + request.getType());
        }
    }

    /**
     * Sends T8803 (存储多媒体数据上传) with type=0 (image) to the device.
     */
    private void handleImageRequest(DeviceFileRequest request) {
        T8803 cmd = buildMediaUploadCommand(request, 0 /* image */);

        markRequested(request.getId(), null, null);
        messageManager.request(cmd, T0001.class)
                .subscribe(
                        resp -> log.info("T8803 succeeded: id={} cid={}", request.getId(), request.getCid()),
                        err -> {
                            log.warn("T8803 failed: id={} cid={} — {}", request.getId(), request.getCid(),
                                    err.getMessage());
                            markFailed(request.getId(), err.getMessage());
                        });
    }

    /**
     * Sends T9206 (文件上传指令) to request video upload to the FTP server.
     * On success the DB record is updated with serialNo, requestedAt, url and
     * status=REQUESTED.
     */
    private void handleVideoRequest(DeviceFileRequest request) {
        T9206 cmd = buildVideoUploadCommand(request);

        messageManager.request(cmd, T0001.class)
                .subscribe(
                        resp -> {
                            String serialNo = String.valueOf(cmd.getSerialNo());
                            String url = buildFtpUrl(cmd);
                            log.info("T9206 succeeded: id={} cid={} serialNo={}",
                                    request.getId(), request.getCid(), serialNo);
                            markRequested(request.getId(), serialNo, url);
                        },
                        err -> {
                            log.warn("T9206 failed: id={} cid={} — {}", request.getId(), request.getCid(),
                                    err.getMessage());
                            markFailed(request.getId(), err.getMessage());
                        });
    }

    /**
     * TODO: Implement log file retrieval when the log-upload protocol class
     * (e.g. T8700) is available. For now the request is immediately marked failed.
     */
    private void handleLogRequest(DeviceFileRequest request) {
        log.warn("Log file request id={} cid={} — log upload command not yet implemented", request.getId(),
                request.getCid());
        markFailed(request.getId(), "Log upload command not yet implemented");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private T8803 buildMediaUploadCommand(DeviceFileRequest request, int mediaType) {
        T8803 cmd = new T8803();
        cmd.setClientId(request.getCid());
        cmd.setType(mediaType);
        cmd.setChannelId(request.getChannel() != null ? request.getChannel() : 0);
        cmd.setEvent(0); // 平台下发指令
        cmd.setStartTime(
                request.getStartTime() != null ? request.getStartTime() : LocalDateTime.of(2000, 1, 1, 0, 0, 0));
        cmd.setEndTime(request.getEndTime() != null ? request.getEndTime() : LocalDateTime.now());
        cmd.setDelete(0); // 保留，不删除
        return cmd;
    }

    private T9206 buildVideoUploadCommand(DeviceFileRequest request) {
        T9206 cmd = jtProperties.newT9206();
        cmd.setClientId(request.getCid());
        cmd.setChannelNo(request.getChannel() != null ? request.getChannel() : 0);
        cmd.setStartTime(
                request.getStartTime() != null ? request.getStartTime() : LocalDateTime.of(2000, 1, 1, 0, 0, 0));
        cmd.setEndTime(request.getEndTime() != null ? request.getEndTime() : LocalDateTime.now());
        cmd.setPath("/" + request.getCid() + "/videos");
        cmd.setWarnBit1(0);
        cmd.setWarnBit2(0);
        cmd.setMediaType(0); // 音视频
        cmd.setStreamType(1); // 主码流
        cmd.setStorageType(0); // 所有存储器
        cmd.setCondition(7); // WiFi | LAN | 3G/4G
        return cmd;
    }

    private String buildFtpUrl(T9206 cmd) {
        return "ftp://" + cmd.getIp() + ":" + cmd.getPort() + cmd.getPath();
    }

    /**
     * Called by {@link org.zendo.web.endpoint.JT1078Endpoint} when a T1206
     * (文件上传完成通知) is received from a device.
     * Finds the pending {@link DeviceFileRequest} by {@code cid + serialNo} and
     * marks it completed or failed depending on {@link T1206#getResult()}.
     */
    public void onFileUploadComplete(T1206 message) {
        String cid = message.getClientId();
        String serialNo = String.valueOf(message.getResponseSerialNo());

        fileRequestRepository.findByCidAndSerialNo(cid, serialNo)
                .ifPresentOrElse(
                        req -> {
                            if (message.isSuccess()) {
                                log.info("T1206 success: id={} cid={} serialNo={}", req.getId(), cid, serialNo);
                                mongoTemplate.updateFirst(
                                        Query.query(Criteria.where("_id").is(req.getId())),
                                        new Update()
                                                .set("status", DeviceFileRequest.Status.COMPLETED.name())
                                                .set("completedAt", LocalDateTime.now()),
                                        DeviceFileRequest.class);
                            } else {
                                log.warn("T1206 failure reported by device: id={} cid={} serialNo={}", req.getId(), cid,
                                        serialNo);
                                markFailed(req.getId(),
                                        "Device reported upload failure (T1206 result=" + message.getResult() + ")");
                            }
                        },
                        () -> log.debug("T1206 received but no matching file request: cid={} serialNo={}", cid,
                                serialNo));
    }

    private void markRequested(String id, String serialNo, String url) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("status", DeviceFileRequest.Status.REQUESTED.name())
                        .set("serialNo", serialNo)
                        .set("url", url)
                        .set("requestedAt", LocalDateTime.now()),
                DeviceFileRequest.class);
    }

    private void markFailed(String id, String reason) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("status", DeviceFileRequest.Status.FAILED.name())
                        .set("failReason", reason),
                DeviceFileRequest.class);
    }
}
