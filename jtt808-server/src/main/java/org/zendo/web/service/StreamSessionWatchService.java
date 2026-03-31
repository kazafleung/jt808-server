package org.zendo.web.service;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.zendo.protocol.t808.T0001;
import org.zendo.protocol.t1078.T9101;
import org.zendo.protocol.t1078.T9102;
import org.zendo.web.config.JTProperties;
import org.zendo.web.endpoint.MessageManager;
import org.zendo.web.model.entity.StreamSession;

import java.util.List;

/**
 * Watches the stream_sessions collection for changes and automatically
 * starts/stops live streams based on subscriber presence:
 * <ul>
 * <li>STREAMING + no subscribers → send T9102 (command=0) to stop the
 * stream</li>
 * <li>STOPPED + subscribers exist → send T9101 to restart the stream</li>
 * </ul>
 * Only acts on devices whose JT808 session is connected to this server
 * instance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamSessionWatchService implements SmartLifecycle {

    private static final String COLLECTION = "stream_sessions";
    private static final long RECONNECT_DELAY_MS = 5_000;

    private final MongoTemplate mongoTemplate;
    private final SessionManager sessionManager;
    private final MessageManager messageManager;
    private final JTProperties jtProperties;

    private volatile boolean running = false;
    private volatile MongoCursor<?> activeCursor;
    private Thread watchThread;

    // ── SmartLifecycle ────────────────────────────────────────────────────────

    @Override
    public void start() {
        running = true;
        watchThread = new Thread(this::watchLoop, "stream-session-watch");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("StreamSessionWatchService started — listening on {}", COLLECTION);
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
        if (watchThread != null)
            watchThread.interrupt();
        log.info("StreamSessionWatchService stopped");
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
                Aggregates.match(
                        Filters.in("operationType", List.of("insert", "update", "replace"))));

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
                    StreamSession session = mongoTemplate.getConverter()
                            .read(StreamSession.class, fullDoc);
                    evaluateAndAct(session);
                }
            }
        } finally {
            activeCursor = null;
        }
    }

    // ── Business logic ────────────────────────────────────────────────────────

    private void evaluateAndAct(StreamSession streamSession) {
        String clientId = streamSession.getClientId();
        if (clientId == null)
            return;

        // Only act on devices whose TCP session is on this server instance
        Session deviceSession = sessionManager.get(clientId);
        if (deviceSession == null)
            return;

        List<StreamSession.Subscriber> subscribers = streamSession.getSubscribers();
        boolean hasSubscribers = subscribers != null && !subscribers.isEmpty();
        StreamSession.Status status = streamSession.getStatus();

        if (!hasSubscribers && status == StreamSession.Status.STREAMING) {
            log.info("No subscribers for clientId={} channelNo={} — sending T9102 stop",
                    clientId, streamSession.getChannelNo());
            stopStream(streamSession);

        } else if (hasSubscribers && status == StreamSession.Status.STOPPED) {
            log.info("Subscribers found for clientId={} channelNo={} (status=STOPPED) — sending T9101 start",
                    clientId, streamSession.getChannelNo());
            startStream(streamSession);

        } else if (status == StreamSession.Status.ERROR) {
            if (hasSubscribers) {
                log.info("Stream ERROR with subscribers for clientId={} channelNo={} — restarting",
                        clientId, streamSession.getChannelNo());
                restartStream(streamSession);
            } else {
                log.info("Stream ERROR with no subscribers for clientId={} channelNo={} — sending T9102 stop",
                        clientId, streamSession.getChannelNo());
                stopStream(streamSession);
            }
        }
    }

    private void restartStream(StreamSession streamSession) {
        T9102 stopRequest = new T9102()
                .setChannelNo(streamSession.getChannelNo())
                .setCommand(0)
                .setCloseType(0)
                .setStreamType(streamSession.getStreamType());
        stopRequest.setClientId(streamSession.getClientId());

        messageManager.request(stopRequest, T0001.class)
                .flatMap(resp -> {
                    JTProperties.C9101 cfg = jtProperties.getT9101();
                    T9101 startRequest = new T9101()
                            .setChannelNo(streamSession.getChannelNo())
                            .setMediaType(streamSession.getMediaType())
                            .setStreamType(streamSession.getStreamType())
                            .setIp(cfg.getIp())
                            .setTcpPort(cfg.getTcpPort())
                            .setUdpPort(cfg.getUdpPort());
                    startRequest.setClientId(streamSession.getClientId());
                    return messageManager.request(startRequest, T0001.class);
                })
                .subscribe(
                        resp -> log.info("Stream restart succeeded: clientId={} channelNo={}",
                                streamSession.getClientId(), streamSession.getChannelNo()),
                        err -> log.warn("Stream restart failed: clientId={} channelNo={} — {}",
                                streamSession.getClientId(), streamSession.getChannelNo(), err.getMessage()));
    }

    private void startStream(StreamSession streamSession) {
        JTProperties.C9101 cfg = jtProperties.getT9101();
        T9101 request = new T9101()
                .setChannelNo(streamSession.getChannelNo())
                .setMediaType(streamSession.getMediaType())
                .setStreamType(streamSession.getStreamType())
                .setIp(cfg.getIp())
                .setTcpPort(cfg.getTcpPort())
                .setUdpPort(cfg.getUdpPort());
        request.setClientId(streamSession.getClientId());

        messageManager.request(request, T0001.class)
                .doOnSuccess(resp -> {
                    mongoTemplate.updateFirst(
                            Query.query(Criteria.where("cid").is(streamSession.getClientId())
                                    .and("cho").is(streamSession.getChannelNo())),
                            new Update()
                                    .set("sip", cfg.getIp())
                                    .set("stp", cfg.getTcpPort())
                                    .set("sup", cfg.getUdpPort())
                                    .set("st", StreamSession.Status.REQUESTED.name()),
                            StreamSession.class);
                })
                .subscribe(
                        resp -> log.info("T9101 auto-start succeeded: clientId={} channelNo={}",
                                streamSession.getClientId(), streamSession.getChannelNo()),
                        err -> log.warn("T9101 auto-start failed: clientId={} channelNo={} — {}",
                                streamSession.getClientId(), streamSession.getChannelNo(), err.getMessage()));
    }

    private void stopStream(StreamSession streamSession) {
        T9102 request = new T9102()
                .setChannelNo(streamSession.getChannelNo())
                .setCommand(0) // 关闭音视频传输
                .setCloseType(0) // 关闭该通道有关的音视频数据
                .setStreamType(streamSession.getStreamType());
        request.setClientId(streamSession.getClientId());

        messageManager.request(request, T0001.class)
                .subscribe(
                        resp -> log.info("T9102 auto-stop succeeded: clientId={} channelNo={}",
                                streamSession.getClientId(), streamSession.getChannelNo()),
                        err -> log.warn("T9102 auto-stop failed: clientId={} channelNo={} — {}",
                                streamSession.getClientId(), streamSession.getChannelNo(), err.getMessage()));
    }
}
