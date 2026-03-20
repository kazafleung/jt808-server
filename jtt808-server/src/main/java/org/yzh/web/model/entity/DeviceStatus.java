package org.yzh.web.model.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.springframework.data.mongodb.core.mapping.Field;
import org.yzh.protocol.t808.T0200;

/**
 * Enriched location snapshot embedded inside a {@link Device} document.
 * Extends {@link LocationRecord} with decoded bit-flag sub-documents so that
 * the device's latest-location field is human-readable without a secondary
 * lookup.
 *
 * {@link LocationRecord} (stored in the high-volume {@code location_records}
 * collection) keeps only the raw {@code warnBit}/{@code statusBit} integers.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class DeviceStatus extends LocationRecord {

    /** 报警标志（按位解码） */
    @Field("w")
    private WarnBits warn;

    /** 状态标志（按位解码） */
    @Field("s")
    private StatusBits status;

    public static DeviceStatus from(T0200 msg) {
        LocationRecord base = LocationRecord.from(msg);
        DeviceStatus dl = new DeviceStatus();
        // copy all base fields
        dl.setId(base.getId());
        dl.setClientId(base.getClientId());
        dl.setDeviceTime(base.getDeviceTime());
        dl.setReceivedAt(base.getReceivedAt());
        dl.setWarnBit(base.getWarnBit());
        dl.setStatusBit(base.getStatusBit());
        dl.setLocation(base.getLocation());
        dl.setAltitude(base.getAltitude());
        dl.setSpeed(base.getSpeed());
        dl.setDirection(base.getDirection());
        dl.setAttributes(base.getAttributes());
        // decoded bit fields
        dl.setWarn(WarnBits.from(msg.getWarnBit()));
        dl.setStatus(StatusBits.from(msg.getStatusBit()));
        return dl;
    }
}
