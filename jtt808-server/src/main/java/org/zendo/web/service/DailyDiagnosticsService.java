package org.zendo.web.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.zendo.protocol.commons.transform.AttributeKey;
import org.zendo.protocol.t808.T0200;
import org.zendo.web.config.DiagnosticsProperties;
import org.zendo.web.model.entity.DeviceDiagDaily;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DailyDiagnosticsService {

    private static final ZoneId JT808_DEVICE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final String DAILY_COLLECTION = "device_diag_daily";

    private final MongoTemplate mongoTemplate;

    /**
     * Adds telemetry diagnostics directly to their event-day documents. A late
     * supplementary upload therefore amends the day when the position was
     * recorded instead of the day when it reached the server.
     */
    public void recordTelemetry(List<T0200> records, DiagnosticsProperties props, int t0704EventCount) {
        if (records == null || records.isEmpty())
            return;

        ZoneId windowZone = ZoneId.of(props.getWindowZone());
        Map<DailyKey, TelemetryBucket> buckets = new HashMap<>();
        for (T0200 record : records) {
            if (record.getClientId() == null || record.getDeviceTime() == null)
                continue;

            LocalDateTime deviceTimeUtc = toUtcDeviceTime(record.getDeviceTime());
            LocalDate date = deviceTimeUtc.atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(windowZone).toLocalDate();
            DailyKey key = new DailyKey(record.getClientId(), date);
            buckets.computeIfAbsent(key, TelemetryBucket::new)
                    .add(record, deviceTimeUtc, props);
        }

        if (buckets.isEmpty())
            return;

        MongoCollection<Document> collection = mongoTemplate.getDb().getCollection(DAILY_COLLECTION);
        List<WriteModel<Document>> writes = new ArrayList<>(buckets.size());
        Date updatedAt = new Date();

        for (TelemetryBucket bucket : buckets.values()) {
            bucket.eventCount = t0704EventCount;
            Date dayStart = dayStart(bucket.key.date, windowZone);
            Document set = dailyIdentity(bucket.key, updatedAt);

            if (bucket.gpsTotal > 0) {
                set.append("dg", buildDailyCounterExpr(
                        "dg", bucket.gpsTotal, bucket.gpsBad, dayStart, null));
            }
            if (bucket.signalTotal > 0) {
                set.append("ds", buildDailyCounterExpr(
                        "ds", bucket.signalTotal, bucket.signalBad, dayStart, null));
            }
            for (Map.Entry<String, int[]> alarm : bucket.alarmCounts.entrySet()) {
                String field = "dw." + alarm.getKey();
                int[] counts = alarm.getValue();
                set.append(field, buildDailyCounterExpr(field, counts[0], counts[1], dayStart, null));
            }

            set.append("dl", buildDailyLocationExpr(
                    bucket.locationTotal,
                    bucket.supplementaryTotal,
                    bucket.supplementaryDurationSeconds(),
                    bucket.eventCount,
                    dayStart));

            if (bucket.firstMileage != null && bucket.lastMileage != null) {
                set.append("ml", buildDailyMileageExpr(
                        bucket.firstMileage.meters,
                        Date.from(bucket.firstMileage.deviceTimeUtc.toInstant(ZoneOffset.UTC)),
                        bucket.lastMileage.meters,
                        Date.from(bucket.lastMileage.deviceTimeUtc.toInstant(ZoneOffset.UTC)),
                        dayStart));
            }

            writes.add(upsert(bucket.key, set));
        }

        collection.bulkWrite(writes, new BulkWriteOptions().ordered(false));
    }

    /**
     * Adds one completed session to all calendar dates it intersects. The event
     * ID is stored in every affected daily document so retrying the same session
     * is a no-op.
     */
    public void recordSession(
            String mobileNo,
            String sessionEventId,
            LocalDateTime onlineAt,
            LocalDateTime offlineAt,
            LocalDateTime accOffStart,
            boolean abnormalOffline,
            ZoneId zone) {

        Map<LocalDate, SessionDelta> deltas = new LinkedHashMap<>();
        splitDurationByDate(onlineAt, offlineAt, zone)
                .forEach((date, seconds) -> deltas.computeIfAbsent(date, ignored -> new SessionDelta()).onlineSeconds += seconds);

        if (accOffStart != null && accOffStart.isBefore(offlineAt)) {
            LocalDateTime boundedStart = accOffStart.isBefore(onlineAt) ? onlineAt : accOffStart;
            splitDurationByDate(boundedStart, offlineAt, zone)
                    .forEach((date, seconds) -> deltas.computeIfAbsent(date, ignored -> new SessionDelta()).accOffSeconds += seconds);
        }

        LocalDate offlineDate = toWindowDate(offlineAt, zone);
        SessionDelta finalDelta = deltas.computeIfAbsent(offlineDate, ignored -> new SessionDelta());
        finalDelta.disconnect = true;
        finalDelta.abnormalDisconnect = abnormalOffline;

        Date updatedAt = new Date();
        List<WriteModel<Document>> writes = new ArrayList<>(deltas.size());
        for (Map.Entry<LocalDate, SessionDelta> entry : deltas.entrySet()) {
            DailyKey key = new DailyKey(mobileNo, entry.getKey());
            SessionDelta delta = entry.getValue();
            Date dayStart = dayStart(key.date, zone);
            long dayLengthSeconds = Duration.between(
                    key.date.atStartOfDay(zone).toInstant(),
                    key.date.plusDays(1).atStartOfDay(zone).toInstant()).getSeconds();

            Document alreadyApplied = new Document("$in", Arrays.asList(
                    sessionEventId,
                    new Document("$ifNull", Arrays.asList("$se", Collections.emptyList()))));

            Document set = dailyIdentity(key, updatedAt);
            if (delta.onlineSeconds > 0) {
                set.append("ol", buildDailyDurationExpr(
                        "ol", delta.onlineSeconds, dayLengthSeconds, dayStart, alreadyApplied));
            }
            if (delta.accOffSeconds > 0) {
                set.append("ao", buildDailyDurationExpr(
                        "ao", delta.accOffSeconds, dayLengthSeconds, dayStart, alreadyApplied));
            }
            if (delta.disconnect) {
                set.append("df", buildDailyCounterExpr(
                        "df", 1, delta.abnormalDisconnect ? 1 : 0, dayStart, alreadyApplied));
            }
            set.append("se", new Document("$setUnion", Arrays.asList(
                    new Document("$ifNull", Arrays.asList("$se", Collections.emptyList())),
                    List.of(sessionEventId))));

            writes.add(upsert(key, set));
        }

        mongoTemplate.getDb().getCollection(DAILY_COLLECTION)
                .bulkWrite(writes, new BulkWriteOptions().ordered(false));
    }

    static Map<LocalDate, Long> splitDurationByDate(
            LocalDateTime startUtc, LocalDateTime endUtc, ZoneId zone) {
        Instant start = startUtc.toInstant(ZoneOffset.UTC);
        Instant end = endUtc.toInstant(ZoneOffset.UTC);
        if (end.isBefore(start))
            throw new IllegalArgumentException("endUtc must not be before startUtc");

        Map<LocalDate, Long> result = new LinkedHashMap<>();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            LocalDate date = cursor.atZone(zone).toLocalDate();
            Instant nextDay = date.plusDays(1).atStartOfDay(zone).toInstant();
            Instant segmentEnd = end.isBefore(nextDay) ? end : nextDay;
            long seconds = Duration.between(cursor, segmentEnd).getSeconds();
            if (seconds > 0)
                result.merge(date, seconds, Long::sum);
            cursor = segmentEnd;
        }
        return result;
    }

    static Document buildDailyCounterExpr(
            String field,
            int batchTotal,
            int batchBad,
            Date dayStart,
            Object alreadyApplied) {
        Object effectiveTotal = conditionalIncrement(batchTotal, alreadyApplied);
        Object effectiveBad = conditionalIncrement(batchBad, alreadyApplied);
        Document newTotal = new Document("$add", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$" + field + ".tot", 0)),
                effectiveTotal));
        Document newBad = new Document("$add", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$" + field + ".bad", 0)),
                effectiveBad));

        return new Document("ws", dayStart)
                .append("tot", newTotal)
                .append("bad", newBad)
                .append("ratio", ratio(newBad, newTotal));
    }

    static Document buildDailyDurationExpr(
            String field,
            long addedSeconds,
            long maximumSeconds,
            Date dayStart,
            Object alreadyApplied) {
        Object effectiveSeconds = conditionalIncrement(addedSeconds, alreadyApplied);
        Document newSeconds = new Document("$min", Arrays.asList(
                new Document("$add", Arrays.asList(
                        new Document("$ifNull", Arrays.asList("$" + field + ".sec", 0L)),
                        effectiveSeconds)),
                maximumSeconds));
        return new Document("ws", dayStart)
                .append("sec", newSeconds)
                .append("base", newSeconds);
    }

    private Document dailyIdentity(DailyKey key, Date updatedAt) {
        Object mongoDate = mongoTemplate.getConverter().convertToMongoType(key.date);
        return new Document("mob", key.mobileNo)
                .append("date", mongoDate)
                .append("cat", new Document("$ifNull", Arrays.asList("$cat", updatedAt)))
                .append("uat", updatedAt);
    }

    static Document buildDailyLocationExpr(
            int batchTotal,
            int batchSupp,
            long durationSeconds,
            long eventCount,
            Date dayStart) {
        Document newTotal = addToExisting("dl.tot", batchTotal, 0);
        Document newBad = addToExisting("dl.bad", batchSupp, 0);
        return new Document("ws", dayStart)
                .append("tot", newTotal)
                .append("bad", newBad)
                .append("ratio", ratio(newBad, newTotal))
                .append("sd", addToExisting("dl.sd", durationSeconds, 0L))
                .append("ec", addToExisting("dl.ec", eventCount, 0L));
    }

    private static Document buildDailyMileageExpr(
            long firstMeters,
            Date firstAt,
            long lastMeters,
            Date lastAt,
            Date dayStart) {
        Object useIncomingStart = new Document("$lt", Arrays.asList(
                firstAt,
                new Document("$ifNull", Arrays.asList("$ml.sat", new Date(253402300799000L)))));
        Object useIncomingEnd = new Document("$gt", Arrays.asList(
                lastAt,
                new Document("$ifNull", Arrays.asList("$ml.eat", new Date(0)))));

        Object nextStartMeters = new Document("$cond", Arrays.asList(
                useIncomingStart,
                firstMeters,
                new Document("$ifNull", Arrays.asList("$ml.start", firstMeters))));
        Object nextEndMeters = new Document("$cond", Arrays.asList(
                useIncomingEnd,
                lastMeters,
                new Document("$ifNull", Arrays.asList("$ml.tot", lastMeters))));

        return new Document("ws", dayStart)
                .append("start", nextStartMeters)
                .append("tot", nextEndMeters)
                .append("day", new Document("$max", Arrays.asList(
                        new Document("$subtract", Arrays.asList(nextEndMeters, nextStartMeters)),
                        0L)))
                .append("sat", new Document("$cond", Arrays.asList(
                        useIncomingStart,
                        firstAt,
                        new Document("$ifNull", Arrays.asList("$ml.sat", firstAt)))))
                .append("eat", new Document("$cond", Arrays.asList(
                        useIncomingEnd,
                        lastAt,
                        new Document("$ifNull", Arrays.asList("$ml.eat", lastAt)))));
    }

    private static Document addToExisting(String field, Object increment, Object defaultValue) {
        return new Document("$add", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$" + field, defaultValue)),
                increment));
    }

    private static Document ratio(Object bad, Object total) {
        return new Document("$cond", Arrays.asList(
                new Document("$eq", Arrays.asList(total, 0)),
                0.0,
                new Document("$divide", Arrays.asList(bad, total))));
    }

    private static Object conditionalIncrement(Object increment, Object alreadyApplied) {
        return alreadyApplied == null
                ? increment
                : new Document("$cond", Arrays.asList(alreadyApplied, 0, increment));
    }

    private static UpdateOneModel<Document> upsert(DailyKey key, Document set) {
        return new UpdateOneModel<>(
                Filters.eq("_id", DeviceDiagDaily.buildId(key.mobileNo, key.date)),
                List.of(new Document("$set", set)),
                new UpdateOptions().upsert(true));
    }

    private static Date dayStart(LocalDate date, ZoneId zone) {
        return Date.from(date.atStartOfDay(zone).toInstant());
    }

    private static LocalDate toWindowDate(LocalDateTime utc, ZoneId zone) {
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate();
    }

    private static LocalDateTime toUtcDeviceTime(LocalDateTime deviceTime) {
        return deviceTime.atZone(JT808_DEVICE_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private record DailyKey(String mobileNo, LocalDate date) {
    }

    private static final class SessionDelta {
        private long onlineSeconds;
        private long accOffSeconds;
        private boolean disconnect;
        private boolean abnormalDisconnect;
    }

    private static final class MileageSample {
        private final LocalDateTime deviceTimeUtc;
        private final long meters;

        private MileageSample(LocalDateTime deviceTimeUtc, long meters) {
            this.deviceTimeUtc = deviceTimeUtc;
            this.meters = meters;
        }
    }

    private static final class TelemetryBucket {
        private final DailyKey key;
        private int gpsTotal;
        private int gpsBad;
        private int signalTotal;
        private int signalBad;
        private int locationTotal;
        private int supplementaryTotal;
        private long eventCount;
        private LocalDateTime supplementaryMin;
        private LocalDateTime supplementaryMax;
        private MileageSample firstMileage;
        private MileageSample lastMileage;
        private final Map<String, int[]> alarmCounts = new HashMap<>();

        private TelemetryBucket(DailyKey key) {
            this.key = key;
        }

        private void add(T0200 record, LocalDateTime deviceTimeUtc, DiagnosticsProperties props) {
            locationTotal++;
            Map<Integer, Object> attributes = record.getAttributes();

            Number gnssCount = numberAttribute(attributes, AttributeKey.GnssCount);
            if (gnssCount != null) {
                gpsTotal++;
                if (gnssCount.intValue() < props.getSatelliteThreshold())
                    gpsBad++;
            }

            Number signalStrength = numberAttribute(attributes, AttributeKey.SignalStrength);
            if (signalStrength != null) {
                signalTotal++;
                if (signalStrength.intValue() < props.getSignalThreshold())
                    signalBad++;
            }

            Number videoWarning = numberAttribute(attributes, AttributeKey.VideoRelatedAlarm);
            if (videoWarning != null) {
                int[] counts = alarmCounts.computeIfAbsent("v14", ignored -> new int[2]);
                counts[0]++;
                if (videoWarning.longValue() != 0)
                    counts[1]++;
            }

            if (record.isSupp()) {
                supplementaryTotal++;
                if (supplementaryMin == null || deviceTimeUtc.isBefore(supplementaryMin))
                    supplementaryMin = deviceTimeUtc;
                if (supplementaryMax == null || deviceTimeUtc.isAfter(supplementaryMax))
                    supplementaryMax = deviceTimeUtc;
            }

            Number mileage = numberAttribute(attributes, AttributeKey.Mileage);
            if (mileage != null) {
                MileageSample sample = new MileageSample(deviceTimeUtc, mileage.longValue() * 100L);
                if (firstMileage == null || sample.deviceTimeUtc.isBefore(firstMileage.deviceTimeUtc))
                    firstMileage = sample;
                if (lastMileage == null || sample.deviceTimeUtc.isAfter(lastMileage.deviceTimeUtc))
                    lastMileage = sample;
            }
        }

        private long supplementaryDurationSeconds() {
            if (supplementaryMin == null || supplementaryMax == null)
                return 0;
            return Duration.between(supplementaryMin, supplementaryMax).getSeconds();
        }

        private static Number numberAttribute(Map<Integer, Object> attributes, int key) {
            if (attributes == null)
                return null;
            Object value = attributes.get(key);
            return value instanceof Number number ? number : null;
        }
    }
}
