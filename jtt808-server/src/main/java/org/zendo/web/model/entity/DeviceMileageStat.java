package org.zendo.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * Rolling-window mileage counter embedded inside a {@link Device} document.
 * Stored as {@code "ml"} field. Tracks total mileage and daily mileage,
 * flushed to {@link DeviceDiagDaily} at day boundaries.
 * 
 * <p>
 * Mileage values are in meters (converted from 0x01 attribute which is in 1/10
 * km).
 */
@Data
@Accessors(chain = true)
public class DeviceMileageStat {

    /**
     * Window start time (UTC) — resets when the calendar-day boundary is crossed.
     */
    @Field("ws")
    private LocalDateTime windowStart;

    /**
     * Total cumulative mileage in meters.
     * This is the absolute mileage value from the device.
     */
    @Field("tot")
    private long totalMeters;

    /**
     * Mileage at the start of the current day window in meters.
     * Used to calculate daily mileage = totalMeters - startMeters.
     */
    @Field("start")
    private long startMeters;

    /**
     * Daily mileage for the current window in meters.
     * Calculated as totalMeters - startMeters.
     */
    @Field("day")
    private long dailyMeters;
}
