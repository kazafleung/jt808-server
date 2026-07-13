package org.zendo.web.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.WriteModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.zendo.protocol.t808.T0200;
import org.zendo.web.config.DiagnosticsProperties;
import org.zendo.web.model.entity.Device;
import org.zendo.web.model.entity.DeviceStatus;
import org.zendo.web.model.entity.LocationRecord;
import org.zendo.web.repository.DeviceRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final MongoTemplate mongoTemplate;
    private final DiagnosticsProperties diagnosticsProperties;
    private final DailyDiagnosticsService dailyDiagnosticsService;

    public Optional<Device> findByMobileNo(String mobileNo) {
        return deviceRepository.findByMobileNo(mobileNo);
    }

    public Device saveOrUpdate(Device device) {
        return deviceRepository.findByMobileNo(device.getMobileNo())
                .map(existing -> {
                    // Targeted fields preserve data owned by the app server.
                    mongoTemplate.updateFirst(
                            Query.query(Criteria.where("_id").is(existing.getId())),
                            new Update()
                                    .set("did", device.getDeviceId())
                                    .set("pln", device.getPlateNo())
                                    .set("pv", device.getProtocolVersion()),
                            Device.class);
                    existing.setDeviceId(device.getDeviceId());
                    existing.setPlateNo(device.getPlateNo());
                    existing.setProtocolVersion(device.getProtocolVersion());
                    return existing;
                })
                .orElseGet(() -> {
                    device.setRegisteredAt(LocalDateTime.now());
                    Device saved = deviceRepository.save(device);
                    log.info("New device registered: mobileNo={}, deviceId={}", saved.getMobileNo(),
                            saved.getDeviceId());
                    return saved;
                });
    }

    /**
     * Set or clear the JT808 instance URL that owns this device's TCP session.
     */
    public void setInstanceUrl(String mobileNo, String instanceUrl) {
        setInstanceUrl(mobileNo, instanceUrl, null, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Clears ownership only when the ending connection still owns the device.
     */
    public void setInstanceUrl(
            String mobileNo,
            String instanceUrl,
            String sessionEventId,
            LocalDateTime occurredAt) {
        boolean isOnline = instanceUrl != null;
        Update update = isOnline
                ? new Update().set("iurl", instanceUrl)
                        .set("sid", sessionEventId)
                        .set("online", true)
                        .set("onlineAt", occurredAt)
                : new Update().unset("iurl")
                        .unset("sid")
                        .set("online", false)
                        .set("offlineAt", occurredAt);
        Criteria criteria = Criteria.where("mob").is(mobileNo);
        if (!isOnline && sessionEventId != null)
            criteria.and("sid").is(sessionEventId);
        mongoTemplate.updateFirst(Query.query(criteria), update, Device.class);
    }

    /** Records a completed TCP session directly into its daily aggregates. */
    public void recordSessionEnd(String mobileNo, LocalDateTime onlineAt, LocalDateTime offlineAt) {
        String eventId = mobileNo + ':' + onlineAt + ':' + offlineAt;
        recordSessionEnd(mobileNo, eventId, onlineAt, offlineAt);
    }

    public void recordSessionEnd(
            String mobileNo,
            String sessionEventId,
            LocalDateTime onlineAt,
            LocalDateTime offlineAt) {
        Objects.requireNonNull(mobileNo, "mobileNo");
        Objects.requireNonNull(sessionEventId, "sessionEventId");
        Objects.requireNonNull(onlineAt, "onlineAt");
        Objects.requireNonNull(offlineAt, "offlineAt");
        if (offlineAt.isBefore(onlineAt))
            throw new IllegalArgumentException("offlineAt must not be before onlineAt");

        ZoneId zone = ZoneId.of(diagnosticsProperties.getWindowZone());
        LocationRecord latest = findLatestLocation(mobileNo, onlineAt, offlineAt);
        boolean abnormalOffline = latest != null && latest.isAccOn() && latest.getSpeed() > 0;
        LocalDateTime accOffStart = findCurrentAccOffStart(mobileNo, onlineAt, latest);

        dailyDiagnosticsService.recordSession(
                mobileNo,
                sessionEventId,
                onlineAt,
                offlineAt,
                accOffStart,
                abnormalOffline,
                zone);
    }

    private LocationRecord findLatestLocation(
            String mobileNo, LocalDateTime onlineAt, LocalDateTime offlineAt) {
        return mongoTemplate.findOne(
                Query.query(Criteria.where("cid").is(mobileNo)
                                .and("dt").gte(onlineAt).lte(offlineAt))
                        .with(Sort.by(Sort.Direction.DESC, "dt"))
                        .limit(1),
                LocationRecord.class);
    }

    private LocalDateTime findCurrentAccOffStart(
            String mobileNo, LocalDateTime onlineAt, LocationRecord latest) {
        if (latest == null || latest.isAccOn() || latest.getDeviceTime() == null)
            return null;

        LocalDateTime latestTime = latest.getDeviceTime();
        if (latestTime.isBefore(onlineAt))
            return null;

        LocalDateTime lowerBound = onlineAt;
        LocationRecord lastAccOn = mongoTemplate.findOne(
                Query.query(Criteria.where("cid").is(mobileNo)
                                .and("acc").is(true)
                                .and("dt").gte(onlineAt).lte(latestTime))
                        .with(Sort.by(Sort.Direction.DESC, "dt"))
                        .limit(1),
                LocationRecord.class);

        boolean includeLowerBound = true;
        if (lastAccOn != null && lastAccOn.getDeviceTime() != null) {
            lowerBound = lastAccOn.getDeviceTime();
            includeLowerBound = false;
        }

        Criteria timeCriteria = includeLowerBound
                ? Criteria.where("dt").gte(lowerBound).lte(latestTime)
                : Criteria.where("dt").gt(lowerBound).lte(latestTime);
        LocationRecord firstAccOff = mongoTemplate.findOne(
                Query.query(Criteria.where("cid").is(mobileNo)
                                .and("acc").is(false)
                                .andOperator(timeCriteria))
                        .with(Sort.by(Sort.Direction.ASC, "dt"))
                        .limit(1),
                LocationRecord.class);

        return firstAccOff != null ? firstAccOff.getDeviceTime() : latestTime;
    }

    /** Updates current device status and atomically accumulates daily diagnostics. */
    public void updateDeviceData(List<T0200> list, DiagnosticsProperties props) {
        updateDeviceData(list, props, 0);
    }

    public void updateDeviceData(List<T0200> list, DiagnosticsProperties props, int t0704EventCount) {
        if (list == null || list.isEmpty())
            return;

        dailyDiagnosticsService.recordTelemetry(list, props, t0704EventCount);

        Map<String, List<T0200>> byDevice = new HashMap<>();
        for (T0200 record : list) {
            if (record.getClientId() == null || record.getDeviceTime() == null)
                continue;
            byDevice.computeIfAbsent(record.getClientId(), ignored -> new ArrayList<>()).add(record);
        }
        if (byDevice.isEmpty())
            return;

        MongoCollection<Document> devices = mongoTemplate.getDb().getCollection("devices");
        List<WriteModel<Document>> writes = new ArrayList<>(byDevice.size());
        for (Map.Entry<String, List<T0200>> entry : byDevice.entrySet()) {
            T0200 latest = entry.getValue().stream()
                    .max(Comparator.comparing(T0200::getDeviceTime))
                    .orElse(null);
            if (latest == null)
                continue;

            DeviceStatus status = DeviceStatus.from(latest);
            Date deviceTime = Date.from(status.getDeviceTime().toInstant(ZoneOffset.UTC));
            Document statusBson = new Document();
            mongoTemplate.getConverter().write(status, statusBson);
            boolean hasValidLocation = latest.getLat() != 0.0 || latest.getLng() != 0.0;

            writes.add(new UpdateOneModel<>(
                    Filters.eq("mob", entry.getKey()),
                    List.of(new Document("$set", new Document(
                            "st", buildStatusUpdateExpr(statusBson, deviceTime, hasValidLocation))))));
        }

        if (!writes.isEmpty())
            devices.bulkWrite(writes, new BulkWriteOptions().ordered(false));
    }

    static Document buildStatusUpdateExpr(Document statusBson, Date deviceTime, boolean hasValidLocation) {
        Document nextStatus = new Document(statusBson);
        if (!hasValidLocation)
            nextStatus.put("loc", "$st.loc");

        return new Document("$cond", Arrays.asList(
                new Document("$lt", Arrays.asList(
                        new Document("$ifNull", Arrays.asList("$st.dt", new Date(0))),
                        deviceTime)),
                nextStatus,
                "$st"));
    }
}
