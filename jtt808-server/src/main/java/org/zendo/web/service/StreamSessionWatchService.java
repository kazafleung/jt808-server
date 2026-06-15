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
import org.zendo.protocol.t1078.T9201;
import org.zendo.protocol.t1078.T9202;
import org.zendo.web.config.JTProperties;
import org.zendo.web.endpoint.MessageManager;
import org.zendo.web.model.entity.StreamSession;

import java.util.List;

/**
 * Watches the stream_sessions collection for changes and automatically
 * starts/stops streams based on subscriber presence:
 * <ul>
 * <li>STREAMING + no subscribers → send T9102/T9202 to stop the stream</li>
 * <li>STOPPED + subscribers exist → send T9101/T9201 to restart the stream</li>
 * </ul>
 * Supports both LIVE streaming (T9101/T9102) and PLAYBACK (T9201/T9202).
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
                                        log.error("Change stream error on '{}', reconnecting in {}ms", COLLECTION,
                                                        RECONNECT_DELAY_MS, e);
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

                StreamSession.StreamType streamType = streamSession.getStreamType();
                StreamSession.Status status = streamSession.getStatus();

                if (streamType == StreamSession.StreamType.PLAYBACK) {
                        // PLAYBACK logic: no subscriber checks, react to status directly
                        if (status == StreamSession.Status.PENDING || status == StreamSession.Status.ERROR) {
                                // PENDING = waiting to request, ERROR = retry
                                log.info("PLAYBACK status={} for clientId={} channelNo={} — sending start command",
                                                status, clientId, streamSession.getChannelNo());
                                startStream(streamSession);

                        } else if (status == StreamSession.Status.STREAMING
                                        || status == StreamSession.Status.REQUESTED) {
                                // Update playback parameters (speed, mode, time) using T9202
                                log.info("PLAYBACK status={} for clientId={} channelNo={} — checking for parameter updates",
                                                status, clientId, streamSession.getChannelNo());
                                updatePlaybackStream(streamSession);

                        } else if (status == StreamSession.Status.STOPPED) {
                                log.info("PLAYBACK status=STOPPED for clientId={} channelNo={} — sending stop command",
                                                clientId, streamSession.getChannelNo());
                                stopStream(streamSession);
                        }

                } else {
                        // LIVE streaming logic: keep existing subscriber-based behavior
                        List<StreamSession.Subscriber> subscribers = streamSession.getSubscribers();
                        boolean hasSubscribers = subscribers != null && !subscribers.isEmpty();

                        if (!hasSubscribers && status == StreamSession.Status.STREAMING) {
                                log.info("LIVE: No subscribers for clientId={} channelNo={} — sending stop command",
                                                clientId, streamSession.getChannelNo());
                                stopStream(streamSession);

                        } else if (hasSubscribers && status == StreamSession.Status.STOPPED) {
                                log.info("LIVE: Subscribers found for clientId={} channelNo={} (status=STOPPED) — sending start command",
                                                clientId, streamSession.getChannelNo());
                                startStream(streamSession);

                        } else if (status == StreamSession.Status.ERROR) {
                                if (hasSubscribers) {
                                        log.info("LIVE: Stream ERROR with subscribers for clientId={} channelNo={} — restarting",
                                                        clientId, streamSession.getChannelNo());
                                        restartStream(streamSession);
                                } else {
                                        log.info("LIVE: Stream ERROR with no subscribers for clientId={} channelNo={} — sending stop command",
                                                        clientId, streamSession.getChannelNo());
                                        stopStream(streamSession);
                                }
                        }
                }
        }

        private void updatePlaybackStream(StreamSession streamSession) {
                // Use T9202 to update playback control parameters (speed, mode, time)
                // Based on pst (start time), pet (end time), ps (playback speed)
                Integer playbackSpeed = streamSession.getPlaybackSpeed();
                Integer playbackMode = streamSession.getPlaybackMode();
                String startTime = streamSession.getStartTime();

                T9202 request = new T9202()
                                .setChannelNo(streamSession.getChannelNo())
                                .setPlaybackTime(startTime)
                                .setPlaybackMode(playbackMode != null ? playbackMode : 0)
                                .setPlaybackSpeed(playbackSpeed != null ? playbackSpeed : 0);

                // If playback mode is 5 (drag/seek), set the playback time
                if (playbackMode != null && playbackMode == 5 && startTime != null) {
                        request.setPlaybackTime(startTime);
                }

                request.setClientId(streamSession.getClientId());

                messageManager.request(request, T0001.class)
                                .subscribe(
                                                resp -> log.info(
                                                                "T9202 playback update succeeded: clientId={} channelNo={} mode={} speed={}",
                                                                streamSession.getClientId(),
                                                                streamSession.getChannelNo(),
                                                                playbackMode, playbackSpeed),
                                                err -> log.warn("T9202 playback update failed: clientId={} channelNo={} — {}",
                                                                streamSession.getClientId(),
                                                                streamSession.getChannelNo(), err.getMessage()));
        }

        private void restartStream(StreamSession streamSession) {
                StreamSession.StreamType streamType = streamSession.getStreamType();

                if (streamType == StreamSession.StreamType.PLAYBACK) {
                        // Stop playback first
                        T9202 stopRequest = new T9202()
                                        .setChannelNo(streamSession.getChannelNo())
                                        .setPlaybackMode(2) // 2=结束回放
                                        .setPlaybackSpeed(0);
                        stopRequest.setClientId(streamSession.getClientId());

                        messageManager.request(stopRequest, T0001.class)
                                        .flatMap(resp -> {
                                                // Restart playback
                                                JTProperties.C9201 cfg = jtProperties.getT9201();
                                                T9201 startRequest = new T9201()
                                                                .setChannelNo(streamSession.getChannelNo())
                                                                .setMediaType(streamSession.getMediaType())
                                                                .setStreamType(streamSession.getCodeStreamType())
                                                                .setIp(cfg.getIp())
                                                                .setTcpPort(cfg.getTcpPort())
                                                                .setUdpPort(cfg.getUdpPort())
                                                                .setStorageType(
                                                                                streamSession.getStorageType() != null
                                                                                                ? streamSession.getStorageType()
                                                                                                : 0)
                                                                .setPlaybackMode(
                                                                                streamSession.getPlaybackMode() != null
                                                                                                ? streamSession.getPlaybackMode()
                                                                                                : 0)
                                                                .setPlaybackSpeed(
                                                                                streamSession.getPlaybackSpeed() != null
                                                                                                ? streamSession.getPlaybackSpeed()
                                                                                                : 0)
                                                                .setStartTime(streamSession.getStartTime())
                                                                .setEndTime(streamSession.getEndTime());
                                                startRequest.setClientId(streamSession.getClientId());
                                                return messageManager.request(startRequest, T0001.class);
                                        })
                                        .subscribe(
                                                        resp -> log.info(
                                                                        "Playback restart succeeded: clientId={} channelNo={}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo()),
                                                        err -> log.warn("Playback restart failed: clientId={} channelNo={} — {}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo(),
                                                                        err.getMessage()));
                } else {
                        // LIVE streaming
                        T9102 stopRequest = new T9102()
                                        .setChannelNo(streamSession.getChannelNo())
                                        .setCommand(0)
                                        .setCloseType(0)
                                        .setStreamType(streamSession.getCodeStreamType());
                        stopRequest.setClientId(streamSession.getClientId());

                        messageManager.request(stopRequest, T0001.class)
                                        .flatMap(resp -> {
                                                JTProperties.C9101 cfg = jtProperties.getT9101();
                                                T9101 startRequest = new T9101()
                                                                .setChannelNo(streamSession.getChannelNo())
                                                                .setMediaType(streamSession.getMediaType())
                                                                .setStreamType(streamSession.getCodeStreamType())
                                                                .setIp(cfg.getIp())
                                                                .setTcpPort(cfg.getTcpPort())
                                                                .setUdpPort(cfg.getUdpPort());
                                                startRequest.setClientId(streamSession.getClientId());
                                                return messageManager.request(startRequest, T0001.class);
                                        })
                                        .subscribe(
                                                        resp -> log.info(
                                                                        "Live stream restart succeeded: clientId={} channelNo={}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo()),
                                                        err -> log.warn("Live stream restart failed: clientId={} channelNo={} — {}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo(),
                                                                        err.getMessage()));
                }
        }

        private void startStream(StreamSession streamSession) {
                StreamSession.StreamType streamType = streamSession.getStreamType();

                if (streamType == StreamSession.StreamType.PLAYBACK) {
                        // Start video playback
                        JTProperties.C9201 cfg = jtProperties.getT9201();
                        T9201 request = new T9201()
                                        .setChannelNo(streamSession.getChannelNo())
                                        .setMediaType(streamSession.getMediaType())
                                        .setStreamType(streamSession.getCodeStreamType())
                                        .setIp(cfg.getIp())
                                        .setTcpPort(cfg.getTcpPort())
                                        .setUdpPort(cfg.getUdpPort())
                                        .setStorageType(streamSession.getStorageType() != null
                                                        ? streamSession.getStorageType()
                                                        : 0)
                                        .setPlaybackMode(streamSession.getPlaybackMode() != null
                                                        ? streamSession.getPlaybackMode()
                                                        : 0)
                                        .setPlaybackSpeed(streamSession.getPlaybackSpeed() != null
                                                        ? streamSession.getPlaybackSpeed()
                                                        : 0)
                                        .setStartTime(streamSession.getStartTime())
                                        .setEndTime(streamSession.getEndTime());
                        request.setClientId(streamSession.getClientId());

                        messageManager.request(request, T0001.class)
                                        .doOnSuccess(resp -> {
                                                mongoTemplate.updateFirst(
                                                                Query.query(Criteria.where("cid")
                                                                                .is(streamSession.getClientId())
                                                                                .and("cho")
                                                                                .is(streamSession.getChannelNo())
                                                                                .and("stype")
                                                                                .is("PLAYBACK")),
                                                                new Update()
                                                                                .set("sip", cfg.getIp())
                                                                                .set("stp", cfg.getTcpPort())
                                                                                .set("sup", cfg.getUdpPort())
                                                                                .set("st", StreamSession.Status.REQUESTED
                                                                                                .name()),
                                                                StreamSession.class);
                                                log.info("T9201 sent and DB updated: clientId={} channelNo={}",
                                                                streamSession.getClientId(),
                                                                streamSession.getChannelNo());
                                        })
                                        .subscribe(
                                                        resp -> log.debug(
                                                                        "T9201 response received: clientId={} channelNo={} resp={}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo(), resp),
                                                        err -> log.warn("T9201 response error (command may have succeeded): clientId={} channelNo={} — {}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo(),
                                                                        err != null ? err.toString() : "null error"));
                } else {
                        // Start live streaming
                        JTProperties.C9101 cfg = jtProperties.getT9101();
                        T9101 request = new T9101()
                                        .setChannelNo(streamSession.getChannelNo())
                                        .setMediaType(streamSession.getMediaType())
                                        .setStreamType(streamSession.getCodeStreamType())
                                        .setIp(cfg.getIp())
                                        .setTcpPort(cfg.getTcpPort())
                                        .setUdpPort(cfg.getUdpPort());
                        request.setClientId(streamSession.getClientId());

                        messageManager.request(request, T0001.class)
                                        .doOnSuccess(resp -> {
                                                mongoTemplate.updateFirst(
                                                                Query.query(Criteria.where("cid")
                                                                                .is(streamSession.getClientId())
                                                                                .and("cho")
                                                                                .is(streamSession.getChannelNo())
                                                                                .and("stype")
                                                                                .is("LIVE")),
                                                                new Update()
                                                                                .set("sip", cfg.getIp())
                                                                                .set("stp", cfg.getTcpPort())
                                                                                .set("sup", cfg.getUdpPort())
                                                                                .set("st", StreamSession.Status.REQUESTED
                                                                                                .name()),
                                                                StreamSession.class);
                                                log.info("T9101 sent and DB updated: clientId={} channelNo={}",
                                                                streamSession.getClientId(),
                                                                streamSession.getChannelNo());
                                        })
                                        .subscribe(
                                                        resp -> log.debug(
                                                                        "T9101 response received: clientId={} channelNo={} resp={}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo(), resp),
                                                        err -> log.warn("T9101 response error (command may have succeeded): clientId={} channelNo={} — {}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo(),
                                                                        err != null ? err.toString() : "null error"));
                }
        }

        private void stopStream(StreamSession streamSession) {
                StreamSession.StreamType streamType = streamSession.getStreamType();

                if (streamType == StreamSession.StreamType.PLAYBACK) {
                        // Stop video playback
                        T9202 request = new T9202()
                                        .setChannelNo(streamSession.getChannelNo())
                                        .setPlaybackMode(2) // 2=结束回放
                                        .setPlaybackSpeed(0);
                        request.setClientId(streamSession.getClientId());

                        messageManager.request(request, T0001.class)
                                        .subscribe(
                                                        resp -> log.info(
                                                                        "T9202 auto-stop succeeded: clientId={} channelNo={}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo()),
                                                        err -> log.warn("T9202 auto-stop failed: clientId={} channelNo={} — {}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo(),
                                                                        err.getMessage()));
                } else {
                        // Stop live streaming
                        T9102 request = new T9102()
                                        .setChannelNo(streamSession.getChannelNo())
                                        .setCommand(0) // 关闭音视频传输
                                        .setCloseType(0) // 关闭该通道有关的音视频数据
                                        .setStreamType(streamSession.getCodeStreamType());
                        request.setClientId(streamSession.getClientId());

                        messageManager.request(request, T0001.class)
                                        .subscribe(
                                                        resp -> log.info(
                                                                        "T9102 auto-stop succeeded: clientId={} channelNo={}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo()),
                                                        err -> log.warn("T9102 auto-stop failed: clientId={} channelNo={} — {}",
                                                                        streamSession.getClientId(),
                                                                        streamSession.getChannelNo(),
                                                                        err.getMessage()));
                }
        }
}
