package org.yzh.web.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.yzh.web.model.entity.StreamSession;

import java.util.List;
import java.util.Optional;

@Repository
public interface StreamSessionRepository extends MongoRepository<StreamSession, String> {

    Optional<StreamSession> findByClientIdAndChannelNo(String clientId, int channelNo);

    List<StreamSession> findByClientId(String clientId);

    List<StreamSession> findByStatus(StreamSession.Status status);
}
