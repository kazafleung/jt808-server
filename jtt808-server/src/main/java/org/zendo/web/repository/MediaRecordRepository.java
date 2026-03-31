package org.zendo.web.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.zendo.web.model.entity.MediaRecord;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MediaRecordRepository extends MongoRepository<MediaRecord, String> {

    Page<MediaRecord> findByClientIdOrderByDeviceTimeDesc(String clientId, Pageable pageable);

    Page<MediaRecord> findByClientIdAndDeviceTimeBetweenOrderByDeviceTimeDesc(
            String clientId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    List<MediaRecord> findByClientIdAndChannelIdOrderByDeviceTimeDesc(String clientId, int channelId);
}
