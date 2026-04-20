package org.zendo.web.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.WriteModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.zendo.protocol.commons.transform.AttributeKey;
import org.zendo.protocol.t808.T0200;
import org.zendo.web.config.DiagnosticsProperties;
import org.zendo.web.model.entity.Device;
import org.zendo.web.model.entity.DeviceStatus;
import org.zendo.web.repository.DeviceRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

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
                    // Use targeted $set so extra fields not mapped in Device (e.g. app-server
                    // fields)
                    // are never touched by a full document replacement.
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
     * Called on sessionRegistered (set) and sessionDestroyed (clear).
     */
    public void setInstanceUrl(String mobileNo, String instanceUrl) {
        Update update = instanceUrl != null
                ? new Update().set("iurl", instanceUrl)
                : new Update().unset("iurl");
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("mob").is(mobileNo)),
                update,
                Device.class);
    }

    /**
     * Unified write: advances the device status snapshot AND increments the
     * rolling-window diagnostic counters in a single MongoDB bulkWrite, so the
     * {@code devices} collection is touched exactly once per batch.
     *
     * <p>
     * <b>Status</b> — updated only when the incoming deviceTime is strictly
     * newer than what is already stored (pipeline {@code $cond}).
     *
     * <p>
     * <b>Diagnostic counters</b> — ALL records in the batch are counted per
     * device (not just the deduplicated latest), so a T0704 bulk-upload of 100
     * records contributes 100 to {@code tot}, not 1.
     */
    public void updateDeviceData(List<T0200> list, DiagnosticsProperties props) {
        if (list == null || list.isEmpty())
            return;

        // Group ALL records by clientId — no deduplication for diagnostic accuracy
        Map<String, List<T0200>> byDevice = new HashMap<>();
        for (T0200 t : list) {
            String clientId = t.getClientId();
            if (clientId == null || t.getDeviceTime() == null)
                continue;
            byDevice.computeIfAbsent(clientId, k -> new ArrayList<>()).add(t);
        }
        if (byDevice.isEmpty())
            return;

        Date cutoff = Date.from(LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(props.getWindowHours()).toInstant(ZoneOffset.UTC));
        Date now = Date.from(LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC));

        MongoCollection<Document> col = mongoTemplate.getDb().getCollection("devices");
        List<WriteModel<Document>> bulk = new ArrayList<>(byDevice.size());

        for (Map.Entry<String, List<T0200>> entry : byDevice.entrySet()) {
            String clientId = entry.getKey();
            List<T0200> records = entry.getValue();

            // Latest record drives the status snapshot
            T0200 latest = records.stream()
                    .max(Comparator.comparing(T0200::getDeviceTime))
                    .orElse(null);
            if (latest == null)
                continue;

            DeviceStatus status = DeviceStatus.from(latest);
            Date deviceTimeBson = Date.from(status.getDeviceTime().toInstant(ZoneOffset.UTC));

            // Serialize via Spring Data so @Field annotations and type converters are
            // respected
            Document statusBson = new Document();
            mongoTemplate.getConverter().write(status, statusBson);

            // Accumulate GPS and signal counts across ALL records in this batch
            int gpsTot = 0, gpsBad = 0, sigTot = 0, sigBad = 0;
            for (T0200 t : records) {
                Map<Integer, Object> attrs = t.getAttributes();
                Integer gnssCount = attrs != null ? (Integer) attrs.get(AttributeKey.GnssCount) : null;
                Integer sigStrength = attrs != null ? (Integer) attrs.get(AttributeKey.SignalStrength) : null;
                if (gnssCount != null) {
                    gpsTot++;
                    if (gnssCount < props.getSatelliteThreshold())
                        gpsBad++;
                }
                if (sigStrength != null) {
                    sigTot++;
                    if (sigStrength < props.getSignalThreshold())
                        sigBad++;
                }
            }

            Document setDoc = new Document();

            // Status: advance only when incoming is strictly newer than stored
            setDoc.append("st", new Document("$cond", Arrays.asList(
                    new Document("$lt", Arrays.asList(
                            new Document("$ifNull", Arrays.asList("$st.dt", new Date(0))),
                            deviceTimeBson)),
                    statusBson,
                    "$st")));

            // Diagnostic counters (only if this batch carries the field)
            if (gpsTot > 0) {
                setDoc.append("dg", buildWindowedCounterExpr("dg", gpsTot, gpsBad, cutoff, now));
            }
            if (sigTot > 0) {
                setDoc.append("ds", buildWindowedCounterExpr("ds", sigTot, sigBad, cutoff, now));
            }

            bulk.add(new UpdateOneModel<>(
                    Filters.eq("mob", clientId),
                    List.of(new Document("$set", setDoc))));
        }

        if (!bulk.isEmpty()) {
            col.bulkWrite(bulk, new BulkWriteOptions().ordered(false));
        }
    }

    /**
     * Builds the aggregation-pipeline expression for a rolling-window counter:
     * resets to {@code {ws: now, tot: batchTot, bad: batchBad}} when the window
     * has expired; otherwise increments the existing counters in-place.
     */
    private static Document buildWindowedCounterExpr(
            String field, int batchTot, int batchBad, Date cutoff, Date now) {

        Document wsOrEpoch = new Document("$ifNull",
                Arrays.asList("$" + field + ".ws", new Date(0)));

        Document isExpired = new Document("$lt", Arrays.asList(wsOrEpoch, cutoff));

        Document resetDoc = new Document("ws", now)
                .append("tot", batchTot)
                .append("bad", batchBad);

        Document incrDoc = new Document("ws", "$" + field + ".ws")
                .append("tot", new Document("$add",
                        Arrays.asList("$" + field + ".tot", batchTot)))
                .append("bad", new Document("$add",
                        Arrays.asList("$" + field + ".bad", batchBad)));

        return new Document("$cond", Arrays.asList(isExpired, resetDoc, incrDoc));
    }
}
