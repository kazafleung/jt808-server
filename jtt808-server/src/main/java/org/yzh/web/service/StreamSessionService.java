package org.yzh.web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t1078.T9102;
import org.yzh.web.model.entity.StreamSession;
import org.yzh.web.repository.StreamSessionRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamSessionService {

    private final StreamSessionRepository streamSessionRepository;
    private final MongoTemplate mongoTemplate;

    /**
     * Called after a T9101 command is successfully sent.
     * Upserts only the stream config fields — never touches the subscribers array,
     * avoiding ObjectId→String type coercion on round-trip.
     */
    public StreamSession startStream(T9101 request) {
        String clientId = request.getClientId();
        int channelNo = request.getChannelNo();

        Query query = Query.query(
                Criteria.where("cid").is(clientId).and("cho").is(channelNo));

        Update update = new Update()
                .set("cid", clientId)
                .set("cho", channelNo)
                .set("tag", StreamSession.buildTag(clientId, channelNo))
                .set("mt", request.getMediaType())
                .set("sty", request.getStreamType())
                .set("sip", request.getIp())
                .set("stp", request.getTcpPort())
                .set("sup", request.getUdpPort())
                .set("st", StreamSession.Status.REQUESTED.name())
                .set("reqAt", LocalDateTime.now(ZoneOffset.UTC))
                .set("upAt", LocalDateTime.now(ZoneOffset.UTC));

        StreamSession saved = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                StreamSession.class);

        log.info("Stream session started: clientId={}, channelNo={}", clientId, channelNo);
        return saved;
    }

    /**
     * Called after a T9102 control command is successfully sent.
     * Updates status based on the command field:
     * 0 = close → STOPPED
     * 1 = switch stream → update streamType only (stays STREAMING/REQUESTED)
     * 2 = pause → PAUSED
     * 3 = resume → STREAMING
     * 4 = close talk → STOPPED
     */
    public Optional<StreamSession> controlStream(T9102 request) {
        return streamSessionRepository
                .findByClientIdAndChannelNo(request.getClientId(), request.getChannelNo())
                .map(session -> {
                    switch (request.getCommand()) {
                        case 0, 4 -> session.setStatus(StreamSession.Status.STOPPED);
                        case 1 -> session.setStreamType(request.getStreamType());
                        case 2 -> session.setStatus(StreamSession.Status.PAUSED);
                        case 3 -> session.setStatus(StreamSession.Status.STREAMING);
                        default -> log.warn("Unknown T9102 command: {}", request.getCommand());
                    }
                    session.markUpdated();
                    return streamSessionRepository.save(session);
                });
    }
}
