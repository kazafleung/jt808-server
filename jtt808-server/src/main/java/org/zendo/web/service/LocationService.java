package org.zendo.web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.zendo.protocol.t808.T0200;
import org.zendo.web.model.entity.LocationRecord;
import org.zendo.web.repository.LocationRepository;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final LocationRepository locationRepository;

    /**
     * Save a batch of T0200 location reports (called from @AsyncBatch handler).
     */
    public void saveBatch(List<T0200> list) {
        List<LocationRecord> records = list.stream()
                .map(LocationRecord::from)
                .toList();
        locationRepository.saveAll(records);
        log.debug("Saved {} location records", records.size());
    }

    /**
     * Save a single T0200 location report.
     */
    public LocationRecord save(T0200 message) {
        LocationRecord record = LocationRecord.from(message);
        return locationRepository.save(record);
    }
}
