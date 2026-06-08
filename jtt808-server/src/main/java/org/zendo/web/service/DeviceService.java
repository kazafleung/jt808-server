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
import org.zendo.web.model.entity.DeviceDiagStat;
import org.zendo.web.model.entity.DeviceStatus;
import org.zendo.web.repository.DeviceRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    /**
     * Keys for alarm types tracked in the {@code dw} map. Add new entries here to
     * track more types.
     */
    private static final List<String> TRACKED_ALARM_KEYS = List.of("v14");

    private final DeviceRepository deviceRepository;
    private final MongoTemplate mongoTemplate;
    private final DiagnosticsProperties diagnosticsProperties;

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
        boolean isOnline = instanceUrl != null;
        Update update = isOnline
                ? new Update().set("iurl", instanceUrl)
                        .set("online", true)
                        .set("onlineAt", LocalDateTime.now(ZoneOffset.UTC))
                : new Update().unset("iurl")
                        .set("online", false)
                        .set("offlineAt", LocalDateTime.now(ZoneOffset.UTC));
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("mob").is(mobileNo)),
                update,
                Device.class);
    }

    /**
     * Accumulates the duration of a completed TCP session into the device's
     * calendar-day online-time counter ({@code ol}). Flushes an expired window
     * to {@link DeviceDiagDaily} before accumulating, following the same pattern
     * as GPS/signal diagnostics.
     * 
     * <p>
     * If the session spans multiple calendar days, the duration is split
     * and recorded to each day proportionally.
     */
    public void recordSessionEnd(String mobileNo, LocalDateTime onlineAt, LocalDateTime offlineAt) {
        long durationSec = Math.max(0, ChronoUnit.SECONDS.between(onlineAt, offlineAt));
        if (durationSec == 0)
            return;

        ZoneId zone = ZoneId.of(diagnosticsProperties.getWindowZone());

        // Determine which day(s) this session belongs to, based on offlineAt in the
        // configured zone
        LocalDate onlineDate = onlineAt.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate();
        LocalDate offlineDate = offlineAt.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate();

        // If session spans multiple days, split the duration
        if (!onlineDate.equals(offlineDate)) {
            // Calculate the split point (midnight boundary between the days)
            LocalDateTime midnightUtc = offlineDate.atStartOfDay(zone)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

            // Duration in the earlier day (onlineAt to midnight)
            long earlyDaySec = Math.max(0, ChronoUnit.SECONDS.between(onlineAt, midnightUtc));
            // Duration in the later day (midnight to offlineAt)
            long lateDaySec = Math.max(0, ChronoUnit.SECONDS.between(midnightUtc, offlineAt));

            // Record the early day portion (if session started yesterday or earlier)
            if (earlyDaySec > 0) {
                LocalDateTime earlyDayStartUtc = onlineDate.atStartOfDay(zone)
                        .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                writeDailySummaries(List.of(mobileNo), earlyDayStartUtc, zone);
                Date earlyDayStartBson = Date.from(earlyDayStartUtc.toInstant(ZoneOffset.UTC));
                mongoTemplate.getDb().getCollection("devices").updateOne(
                        Filters.eq("mob", mobileNo),
                        List.of(new Document("$set",
                                new Document("ol", buildOnlineCounterExpr(earlyDaySec, earlyDayStartBson)))));
            }

            // Record the late day portion (today)
            if (lateDaySec > 0) {
                LocalDateTime lateDayStartUtc = offlineDate.atStartOfDay(zone)
                        .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                writeDailySummaries(List.of(mobileNo), lateDayStartUtc, zone);
                Date lateDayStartBson = Date.from(lateDayStartUtc.toInstant(ZoneOffset.UTC));
                mongoTemplate.getDb().getCollection("devices").updateOne(
                        Filters.eq("mob", mobileNo),
                        List.of(new Document("$set",
                                new Document("ol", buildOnlineCounterExpr(lateDaySec, lateDayStartBson)))));
            }
        } else {
            // Session is within a single calendar day
            LocalDateTime dayStartUtc = offlineDate.atStartOfDay(zone)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

            writeDailySummaries(List.of(mobileNo), dayStartUtc, zone);

            Date dayStartBson = Date.from(dayStartUtc.toInstant(ZoneOffset.UTC));
            mongoTemplate.getDb().getCollection("devices").updateOne(
                    Filters.eq("mob", mobileNo),
                    List.of(new Document("$set",
                            new Document("ol", buildOnlineCounterExpr(durationSec, dayStartBson)))));
        }
    }

    /**
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

            // Check if location coordinates are valid (not 0,0)
            boolean hasValidLocation = latest.getLat() != 0.0 || latest.getLng() != 0.0;

            // If location is invalid, remove it from the status update to preserve existing
            // valid location
            if (!hasValidLocation) {
                statusBson.remove("loc");
            }

            // Extract mileage from latest record (attribute 0x01, in 1/10 km, convert to
            // meters)
            Long mileageMeters = null;
            Map<Integer, Object> latestAttrs = latest.getAttributes();
            if (latestAttrs != null) {
                Long mileageTenthKm = (Long) latestAttrs.get(AttributeKey.Mileage);
                if (mileageTenthKm != null) {
                    mileageMeters = mileageTenthKm * 100; // 1/10 km = 100 meters
                }
            }

            int gpsTot = 0, gpsBad = 0, sigTot = 0, sigBad = 0;
            int locTot = records.size(), locSupp = 0; // Count all records and supplementary ones
            Map<String, int[]> alarmCounts = new HashMap<>();
            for (T0200 t : records) {
                Map<Integer, Object> attrs = t.getAttributes();
                Integer gnssCount = attrs != null ? (Integer) attrs.get(AttributeKey.GnssCount) : null;
                Integer sigStrength = attrs != null ? (Integer) attrs.get(AttributeKey.SignalStrength) : null;
                Integer videoWarn = attrs != null ? (Integer) attrs.get(AttributeKey.VideoRelatedAlarm) : null;
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
                if (videoWarn != null) {
                    int[] c = alarmCounts.computeIfAbsent("v14", k -> new int[2]);
                    c[0]++;
                    if (videoWarn != 0)
                        c[1]++;
                }
                // Count supplementary (batch) uploads
                if (t.isSupp()) {
                    locSupp++;
                }
            }

            Document setDoc = new Document();

            // Status: advance only when incoming is strictly newer than stored
            // When location is invalid (0,0), update all fields except location (which was
            // removed above)
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
            for (Map.Entry<String, int[]> e : alarmCounts.entrySet()) {
                String path = "dw." + e.getKey();
                int[] c = e.getValue();
                setDoc.append(path, buildWindowedCounterExpr(path, c[0], c[1], todayStartBson));
            }

            // Update online time counter for currently connected devices
            setDoc.append("ol", buildOnlineTimeWithCurrentSession(todayStartBson));

            // Update mileage counter if mileage data is available
            if (mileageMeters != null) {
                setDoc.append("ml", buildMileageCounterExpr(mileageMeters, todayStartBson));
            }

            // Update location statistics (total count and supplementary count)
            setDoc.append("dl", buildWindowedCounterExpr("dl", locTot, locSupp, todayStartBson));

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

        List<Criteria> expiredList = new ArrayList<>(List.of(expiredGps, expiredSig));
        for (String k : TRACKED_ALARM_KEYS) {
            expiredList.add(Criteria.where("dw." + k + ".ws").lt(todayStartUtc)
                    .and("dw." + k + ".tot").gt(0));
        }
        expiredList.add(Criteria.where("ol.ws").lt(todayStartUtc)
                .and("ol.sec").gt(0));
        expiredList.add(Criteria.where("ml.ws").lt(todayStartUtc)
                .and("ml.day").gt(0));
        expiredList.add(Criteria.where("dl.ws").lt(todayStartUtc)
                .and("dl.tot").gt(0));

        List<Device> expired = mongoTemplate.find(
                Query.query(Criteria.where("mob").in(clientIds)
                        .orOperator(expiredList.toArray(new Criteria[0]))),
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
                    .setAlarms(d.getDiagAlarms())
                    .setOnline(d.getDiagOnline())
                    .setMileage(d.getDiagMileage())
                    .setLocation(d.getDiagLoc())
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
        else if (d.getDiagAlarms() != null) {
            ws = d.getDiagAlarms().values().stream()
                    .filter(s -> s != null && s.getWindowStart() != null)
                    .map(DeviceDiagStat::getWindowStart)
                    .findFirst().orElse(null);
        }
        if (ws == null && d.getDiagOnline() != null)
            ws = d.getDiagOnline().getWindowStart();
        if (ws == null && d.getDiagMileage() != null)
            ws = d.getDiagMileage().getWindowStart();
        if (ws == null && d.getDiagLoc() != null)
            ws = d.getDiagLoc().getWindowStart();
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

    /**
     * Builds the aggregation-pipeline expression for the online-time counter:
     * adds completed session duration to both `base` (completed only) and `sec`
     * (total).
     * Resets on window expiration.
     */
    private static Document buildOnlineCounterExpr(long durationSec, Date todayStart) {
        Document wsOrEpoch = new Document("$ifNull", Arrays.asList("$ol.ws", new Date(0)));
        Document isExpired = new Document("$lt", Arrays.asList(wsOrEpoch, todayStart));

        // Reset: new day, set base and sec to this session's duration
        Document resetDoc = new Document("ws", todayStart)
                .append("base", durationSec)
                .append("sec", durationSec);

        // Increment: add duration to existing base
        Document existingBase = new Document("$ifNull", Arrays.asList("$ol.base", 0L));
        Document newBase = new Document("$add", Arrays.asList(existingBase, durationSec));
        Document incrDoc = new Document("ws", "$ol.ws")
                .append("base", newBase)
                .append("sec", newBase); // sec = base after session ends (no active session)

        return new Document("$cond", Arrays.asList(isExpired, resetDoc, incrDoc));
    }

    /**
     * Builds an expression that updates the online-time counter including the
     * current active session (if device is online).
     * 
     * <p>
     * Structure: ol = {ws: Date, sec: Long, base: Long}
     * <ul>
     * <li>ws: window start (today's midnight)</li>
     * <li>sec: total seconds today (base + current session)</li>
     * <li>base: completed sessions only (updated by recordSessionEnd)</li>
     * </ul>
     * 
     * <p>
     * For online devices, calculates current session duration from
     * max(onlineAt, todayStart) to now, and adds it to base.
     * For offline devices, sec = base.
     */
    private static Document buildOnlineTimeWithCurrentSession(Date todayStart) {
        Date now = new Date();

        // Check if window is expired
        Document wsOrEpoch = new Document("$ifNull", Arrays.asList("$ol.ws", new Date(0)));
        Document isExpired = new Document("$lt", Arrays.asList(wsOrEpoch, todayStart));

        // Calculate current session duration (if online)
        Document isOnline = new Document("$eq", Arrays.asList("$online", true));
        Document onlineAtOrNull = new Document("$ifNull", Arrays.asList("$onlineAt", now));

        // Session start for today = max(onlineAt, todayStart)
        Document sessionStartToday = new Document("$max", Arrays.asList(onlineAtOrNull, todayStart));

        // Current session duration in seconds
        Document currentSessionMs = new Document("$subtract", Arrays.asList(now, sessionStartToday));
        Document currentSessionSec = new Document("$toLong",
                new Document("$divide", Arrays.asList(currentSessionMs, 1000L)));
        Document currentSessionSecSafe = new Document("$max", Arrays.asList(currentSessionSec, 0L));

        // === Window expired (new day) ===
        // Reset base to 0, sec to current session (if online) or 0
        Document resetSec = new Document("$cond", Arrays.asList(isOnline, currentSessionSecSafe, 0L));
        Document resetDoc = new Document("ws", todayStart)
                .append("base", 0L)
                .append("sec", resetSec);

        // === Window not expired (same day) ===
        // base stays the same (completed sessions), sec = base + current session
        Document existingBase = new Document("$ifNull", Arrays.asList("$ol.base", 0L));
        Document totalSec = new Document("$cond", Arrays.asList(
                isOnline,
                new Document("$add", Arrays.asList(existingBase, currentSessionSecSafe)),
                existingBase)); // If offline, sec = base only
        Document incrDoc = new Document("ws", "$ol.ws")
                .append("base", existingBase)
                .append("sec", totalSec);

        return new Document("$cond", Arrays.asList(isExpired, resetDoc, incrDoc));
    }

    /**
     * Builds the aggregation-pipeline expression for mileage tracking:
     * <ul>
     * <li>ws: window start (today's midnight)</li>
     * <li>tot: total cumulative mileage in meters</li>
     * <li>start: mileage at start of current day window</li>
     * <li>day: daily mileage = tot - start</li>
     * </ul>
     * 
     * <p>
     * When the window expires (new day), resets startMeters to currentMileage
     * and dailyMeters to 0. Otherwise, updates totalMeters and calculates
     * dailyMeters = totalMeters - startMeters.
     * 
     * @param currentMileageMeters the latest mileage reading in meters
     * @param todayStart           the start of the current day window
     * @return MongoDB aggregation expression for mileage tracking
     */
    private static Document buildMileageCounterExpr(long currentMileageMeters, Date todayStart) {
        // Check if window is expired
        Document wsOrEpoch = new Document("$ifNull", Arrays.asList("$ml.ws", new Date(0)));
        Document isExpired = new Document("$lt", Arrays.asList(wsOrEpoch, todayStart));

        // === Window expired (new day) ===
        // Reset: tot = current, start = current, day = 0
        Document resetDoc = new Document("ws", todayStart)
                .append("tot", currentMileageMeters)
                .append("start", currentMileageMeters)
                .append("day", 0L);

        // === Window not expired (same day) ===
        // tot = current, start = existing start, day = tot - start
        Document existingStart = new Document("$ifNull", Arrays.asList("$ml.start", currentMileageMeters));
        Document dailyMileage = new Document("$max", Arrays.asList(
                new Document("$subtract", Arrays.asList(currentMileageMeters, existingStart)),
                0L)); // Ensure non-negative
        Document incrDoc = new Document("ws", "$ml.ws")
                .append("tot", currentMileageMeters)
                .append("start", existingStart)
                .append("day", dailyMileage);

        return new Document("$cond", Arrays.asList(isExpired, resetDoc, incrDoc));
    }
}
