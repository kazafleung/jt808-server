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
import org.zendo.web.model.entity.DeviceDiagDaily;
import org.zendo.web.model.entity.DeviceStatus;
import org.zendo.web.repository.DeviceRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
     * calendar-day diagnostic counters in a single MongoDB bulkWrite.
     *
     * <p>
     * <b>Window</b> — each counter covers one full calendar day
     * ({@code 00:00–23:59}) in the configured timezone. When the first update
     * after midnight arrives, the old day's counters are saved as a
     * {@link DeviceDiagDaily} summary before being reset.
     *
     * <p>
     * <b>Concurrency</b> — summaries use a composite {@code _id}
     * ({@code mobileNo_YYYY-MM-DD}) so concurrent resets from multiple threads
     * or server instances are idempotent.
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

        // Calendar-day window boundaries — zone-aware, but stored in UTC
        ZoneId zone = ZoneId.of(props.getWindowZone());
        LocalDateTime todayStartUtc = LocalDate.now(zone)
                .atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Date todayStartBson = Date.from(todayStartUtc.toInstant(ZoneOffset.UTC));

        // Save daily summaries for devices whose window has just expired
        writeDailySummaries(new ArrayList<>(byDevice.keySet()), todayStartUtc, zone);

        MongoCollection<Document> col = mongoTemplate.getDb().getCollection("devices");
        List<WriteModel<Document>> bulk = new ArrayList<>(byDevice.size());

        for (Map.Entry<String, List<T0200>> entry : byDevice.entrySet()) {
            String clientId = entry.getKey();
            List<T0200> records = entry.getValue();

            T0200 latest = records.stream()
                    .max(Comparator.comparing(T0200::getDeviceTime))
                    .orElse(null);
            if (latest == null)
                continue;

            DeviceStatus status = DeviceStatus.from(latest);
            Date deviceTimeBson = Date.from(status.getDeviceTime().toInstant(ZoneOffset.UTC));

            Document statusBson = new Document();
            mongoTemplate.getConverter().write(status, statusBson);

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

            if (gpsTot > 0) {
                setDoc.append("dg", buildWindowedCounterExpr("dg", gpsTot, gpsBad, todayStartBson));
            }
            if (sigTot > 0) {
                setDoc.append("ds", buildWindowedCounterExpr("ds", sigTot, sigBad, todayStartBson));
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
     * For devices in {@code clientIds} that have an expired window
     * ({@code dg.ws} or {@code ds.ws} before today), writes a
     * {@link DeviceDiagDaily} summary record and returns. The composite
     * {@code _id} makes the upsert idempotent.
     */
    private void writeDailySummaries(
            List<String> clientIds, LocalDateTime todayStartUtc, ZoneId zone) {

        Criteria expiredGps = Criteria.where("dg.ws").lt(todayStartUtc)
                .and("dg.tot").gt(0);
        Criteria expiredSig = Criteria.where("ds.ws").lt(todayStartUtc)
                .and("ds.tot").gt(0);

        List<Device> expired = mongoTemplate.find(
                Query.query(Criteria.where("mob").in(clientIds)
                        .orOperator(expiredGps, expiredSig)),
                Device.class);

        for (Device d : expired) {
            LocalDate summaryDate = resolveSummaryDate(d, zone);
            if (summaryDate == null)
                continue;

            DeviceDiagDaily summary = new DeviceDiagDaily()
                    .setId(DeviceDiagDaily.buildId(d.getMobileNo(), summaryDate))
                    .setMobileNo(d.getMobileNo())
                    .setDate(summaryDate)
                    .setGps(d.getDiagGps())
                    .setSignal(d.getDiagSig())
                    .setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));

            mongoTemplate.save(summary);
        }
    }

    /**
     * Returns the calendar date of the expired window, derived from whichever
     * {@link org.zendo.web.model.entity.DeviceDiagStat} has a non-null
     * {@code windowStart}.
     */
    private static LocalDate resolveSummaryDate(Device d, ZoneId zone) {
        LocalDateTime ws = null;
        if (d.getDiagGps() != null && d.getDiagGps().getWindowStart() != null)
            ws = d.getDiagGps().getWindowStart();
        else if (d.getDiagSig() != null && d.getDiagSig().getWindowStart() != null)
            ws = d.getDiagSig().getWindowStart();
        if (ws == null)
            return null;
        return ws.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate();
    }

    /**
     * Builds the aggregation-pipeline expression for a calendar-day counter:
     * resets to {@code {ws: todayStart, tot: batchTot, bad: batchBad, ratio: ...}}
     * when the window has expired (ws &lt; todayStart); otherwise increments
     * the existing counters in-place.
     *
     * <p>
     * Using {@code todayStart} (not {@code now}) as the new {@code ws}
     * ensures all resets within the same day share the same window-start value.
     */
    private static Document buildWindowedCounterExpr(
            String field, int batchTot, int batchBad, Date todayStart) {

        Document wsOrEpoch = new Document("$ifNull",
                Arrays.asList("$" + field + ".ws", new Date(0)));

        Document isExpired = new Document("$lt", Arrays.asList(wsOrEpoch, todayStart));

        Document resetDoc = new Document("ws", todayStart)
                .append("tot", batchTot)
                .append("bad", batchBad)
                .append("ratio", batchTot == 0 ? 0.0 : (double) batchBad / batchTot);

        Document newTot = new Document("$add", Arrays.asList("$" + field + ".tot", batchTot));
        Document newBad = new Document("$add", Arrays.asList("$" + field + ".bad", batchBad));
        Document newRatio = new Document("$cond", Arrays.asList(
                new Document("$eq", Arrays.asList(newTot, 0)),
                0.0,
                new Document("$divide", Arrays.asList(newBad, newTot))));

        Document incrDoc = new Document("ws", "$" + field + ".ws")
                .append("tot", newTot)
                .append("bad", newBad)
                .append("ratio", newRatio);

        return new Document("$cond", Arrays.asList(isExpired, resetDoc, incrDoc));
    }
}
