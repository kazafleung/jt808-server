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
     * Uses $set on individual fields — never touches the subscribers array.
     * 0 = close → STOPPED
     * 1 = switch stream → update streamType only
     * 2 = pause → PAUSED
     * 3 = resume → STREAMING
     * 4 = close talk → STOPPED
     */
    public Optional<StreamSession> controlStream(T9102 request) {
        Query query = Query.query(
                Criteria.where("cid").is(request.getClientId()).and("cho").is(request.getChannelNo()));

        Update update = new Update().set("upAt", LocalDateTime.now(ZoneOffset.UTC));
        switch (request.getCommand()) {
            case 0, 4 -> update.set("st", StreamSession.Status.STOPPED.name());
            case 1 -> update.set("sty", request.getStreamType());
            case 2 -> update.set("st", StreamSession.Status.PAUSED.name());
            case 3 -> update.set("st", StreamSession.Status.STREAMING.name());
            default -> {
                log.warn("Unknown T9102 command: {}", request.getCommand());
                return Optional.empty();
            }
        }

        StreamSession result = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                StreamSession.class);
        return Optional.ofNullable(result);
    }
}
