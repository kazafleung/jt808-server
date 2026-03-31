package org.zendo.web.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.zendo.web.model.entity.LocationRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LocationRepository extends MongoRepository<LocationRecord, String> {

    /** Latest location for a device */
    Optional<LocationRecord> findTopByClientIdOrderByDeviceTimeDesc(String clientId);

    /** Location history for a device within a time range (paged) */
    Page<LocationRecord> findByClientIdAndDeviceTimeBetweenOrderByDeviceTimeDesc(
            String clientId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /** All records uploaded in a bulk T0704 batch (same clientId, same receivedAt second) */
    List<LocationRecord> findByClientIdAndReceivedAtBetween(
            String clientId, LocalDateTime from, LocalDateTime to);
}
