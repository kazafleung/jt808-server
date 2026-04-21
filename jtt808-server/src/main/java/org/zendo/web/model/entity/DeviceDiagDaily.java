package org.zendo.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Daily snapshot of a device's GPS and signal quality counters, written once
 * per device per calendar day when the day-boundary window expires.
 *
 * <p>
 * The composite {@code _id} ({@code mobileNo_YYYY-MM-DD}) makes writes
 * idempotent — concurrent resets from multiple server instances safely
 * overwrite each other with the same data.
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

    /** When this summary record was written (UTC). */
    @Field("cat")
    private LocalDateTime createdAt;

    public static String buildId(String mobileNo, LocalDate date) {
        return mobileNo + "_" + date;
    }
}
