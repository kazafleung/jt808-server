package org.yzh.web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.yzh.protocol.t808.T0200;
import org.yzh.web.model.entity.Device;
import org.yzh.web.model.entity.LocationRecord;
import org.yzh.web.repository.DeviceRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final MongoTemplate mongoTemplate;

    /**
     * Upsert device on T0100 registration.
     * If the device (by mobileNo) already exists, update its info.
     * Returns the saved document (with MongoDB _id populated).
     */
    public Optional<Device> findByMobileNo(String mobileNo) {
        return deviceRepository.findByMobileNo(mobileNo);
    }

    public Device saveOrUpdate(Device device) {
        return deviceRepository.findByMobileNo(device.getMobileNo())
                .map(existing -> {
                    // update mutable fields, preserve registeredAt
                    existing.setDeviceId(device.getDeviceId());
                    existing.setPlateNo(device.getPlateNo());
                    existing.setProtocolVersion(device.getProtocolVersion());
                    return deviceRepository.save(existing);
                })
                .orElseGet(() -> {
                    device.setRegisteredAt(LocalDateTime.now());
                    Device saved = deviceRepository.save(device);
                    log.info("New device registered: mobileNo={}, deviceId={}", saved.getMobileNo(), saved.getDeviceId());
                    return saved;
                });
    }

    /**
     * Bulk-update each device's latest location in MongoDB.
     * Only writes if the incoming deviceTime is newer than what is stored.
     */
    public void updateLatestLocations(List<T0200> list) {
        if (list == null || list.isEmpty()) return;

        // Deduplicate: keep only the newest T0200 per clientId
        Map<String, T0200> latestByDevice = new HashMap<>();
        for (T0200 t : list) {
            String clientId = t.getClientId();
            if (clientId == null || t.getDeviceTime() == null) continue;
            latestByDevice.merge(clientId, t, (a, b) ->
                    a.getDeviceTime().isBefore(b.getDeviceTime()) ? b : a);
        }

        if (latestByDevice.isEmpty()) return;

        BulkOperations ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Device.class);
        latestByDevice.forEach((clientId, t) -> {
            LocationRecord record = LocationRecord.from(t);
            Query q = Query.query(Criteria.where("mobileNo").is(clientId)
                    .orOperator(
                            Criteria.where("location").isNull(),
                            Criteria.where("location.deviceTime").lt(record.getDeviceTime())
                    ));
            ops.updateOne(q, Update.update("location", record));
        });
        ops.execute();
    }
}
