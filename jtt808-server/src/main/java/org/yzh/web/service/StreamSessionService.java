package org.yzh.web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Called after a T9101 command is successfully sent.
     * Creates or replaces the stream session record with REQUESTED status.
     */
    public StreamSession startStream(T9101 request) {
        StreamSession session = streamSessionRepository
                .findByClientIdAndChannelNo(request.getClientId(), request.getChannelNo())
                .orElse(new StreamSession());

        session.setClientId(request.getClientId())
                .setChannelNo(request.getChannelNo())
                .setMediaType(request.getMediaType())
                .setStreamType(request.getStreamType())
                .setServerIp(request.getIp())
                .setServerTcpPort(request.getTcpPort())
                .setServerUdpPort(request.getUdpPort())
                .setStatus(StreamSession.Status.REQUESTED)
                .setRequestedAt(LocalDateTime.now(ZoneOffset.UTC))
                .markUpdated();

        StreamSession saved = streamSessionRepository.save(session);
        log.info("Stream session started: clientId={}, channelNo={}", saved.getClientId(), saved.getChannelNo());
        return saved;
    }

    /**
     * Called after a T9102 control command is successfully sent.
     * Updates status based on the command field:
     *   0 = close → STOPPED
     *   1 = switch stream → update streamType only (stays STREAMING/REQUESTED)
     *   2 = pause → PAUSED
     *   3 = resume → STREAMING
     *   4 = close talk → STOPPED
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
