package org.zendo.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * Rolling-window diagnostic counter embedded inside a {@link Device} document.
 * Stored as {@code "dg"} (GPS) and {@code "ds"} (signal) fields.
 *
 * <p>
 * The window resets lazily whenever a location update arrives after
 * {@code ws + windowHours} has elapsed — handled atomically via a MongoDB
 * aggregation-pipeline update in {@code DeviceService}.
 */
@Data
@Accessors(chain = true)
public class DeviceDiagStat {

    /** Window start time (UTC) — resets when the window expires. */
    @Field("ws")
    private LocalDateTime windowStart;

    /** Total location records counted in the current window. */
    @Field("tot")
    private int total;

    /** Records in the window that were below the configured quality threshold. */
    @Field("bad")
    private int bad;

    /**
     * Pre-computed ratio (bad/tot); stored for indexed range queries by the app
     * server.
     */
    @Field("ratio")
    private double ratio;

    /**
     * Accumulated supplement duration in seconds for supplementary (0x704) records.
     * Incremented each time a 0x704 message is processed.
     */
    @Field("sd")
    private Long suppDurationSec;

    /**
     * T0704 event count. For location diagnostics, {@code tot} tracks the number
     * of location records while this tracks the number of batch-upload messages.
     */
    @Field("ec")
    private Long eventCount;
}
