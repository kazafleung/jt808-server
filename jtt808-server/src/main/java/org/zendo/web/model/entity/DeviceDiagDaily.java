package org.zendo.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Live daily diagnostic aggregate. Telemetry and completed sessions update the
 * document atomically using the composite {@code mobileNo_YYYY-MM-DD} ID.
 */
@Data
@Accessors(chain = true)
@Document(collection = "device_diag_daily")
@CompoundIndex(name = "mob_date", def = "{'mob': 1, 'date': -1}")
public class DeviceDiagDaily {

    /** Composite key: {@code mobileNo_YYYY-MM-DD}. */
    @Id
    private String id;

    @Field("mob")
    private String mobileNo;

    /** Calendar date (in the configured window timezone) this summary covers. */
    @Field("date")
    private LocalDate date;

    /** GPS quality counters for the day. */
    @Field("dg")
    private DeviceDiagStat gps;

    /** Signal strength counters for the day. */
    @Field("ds")
    private DeviceDiagStat signal;

    /** Per-type alarm counters for the day (key = alarm type, e.g. "v14"). */
    @Field("dw")
    private Map<String, DeviceDiagStat> alarms;

    /** Online time for the day. */
    @Field("ol")
    private DeviceOnlineStat online;

    /** Mileage for the day. */
    @Field("ml")
    private DeviceMileageStat mileage;

    /** Location update statistics for the day (supplementary/total). */
    @Field("dl")
    private DeviceDiagStat location;

    /** Disconnect statistics for the day (abnormal/total). */
    @Field("df")
    private DeviceDiagStat offline;

    /** Working time after ACC off for the day. */
    @Field("ao")
    private DeviceOnlineStat accOffWork;

    /** When this summary record was written (UTC). */
    @Field("cat")
    private LocalDateTime createdAt;

    /** Last time this live daily aggregate was updated (UTC). */
    @Field("uat")
    private LocalDateTime updatedAt;

    /** Session event IDs already applied to this date, used for idempotency. */
    @Field("se")
    private List<String> sessionEventIds;

    public static String buildId(String mobileNo, LocalDate date) {
        return mobileNo + "_" + date;
    }
}
