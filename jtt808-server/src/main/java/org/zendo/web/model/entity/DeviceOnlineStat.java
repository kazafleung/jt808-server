package org.zendo.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * Rolling-window online-time counter embedded inside a {@link Device} document.
 * Stored as {@code "ol"} field. Accumulated on each TCP session disconnect
 * and flushed to {@link DeviceDiagDaily} at day boundaries.
 * 
 * <p>
 * The counter tracks both completed sessions ({@code base}) and includes
 * the current active session duration when queried during updateDeviceData.
 */
@Data
@Accessors(chain = true)
public class DeviceOnlineStat {

    /**
     * Window start time (UTC) — resets when the calendar-day boundary is crossed.
     */
    @Field("ws")
    private LocalDateTime windowStart;

    /**
     * Total seconds the device was online during the current window.
     * For online devices, this includes both completed sessions (base) and
     * the current active session duration.
     */
    @Field("sec")
    private long seconds;

    /**
     * Completed sessions only (base value). Updated when sessions end.
     * The current active session duration is calculated and added to this
     * during updateDeviceData.
     */
    @Field("base")
    private long baseSeconds;
}
