package org.zendo.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/** Online or ACC-off duration stored in a daily diagnostic document. */
@Data
@Accessors(chain = true)
public class DeviceOnlineStat {

    /**
     * Window start time (UTC) — resets when the calendar-day boundary is crossed.
     */
    @Field("ws")
    private LocalDateTime windowStart;

    /**
     * Total completed-session seconds for the calendar date.
     */
    @Field("sec")
    private long seconds;

    /**
     * Compatibility mirror of {@link #seconds}.
     */
    @Field("base")
    private long baseSeconds;
}
