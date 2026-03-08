package org.yzh.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.yzh.protocol.t808.T0200;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * MongoDB document for JT808 T0200 location reports
 */
@Data
@Accessors(chain = true)
@Document(collection = "location_records")
@CompoundIndexes({
        @CompoundIndex(name = "clientId_deviceTime", def = "{'clientId': 1, 'deviceTime': -1}")
})
public class LocationRecord {

    @Id
    private String id;

    /** 终端手机号 (device phone number / client ID) */
    @Indexed
    private String clientId;

    /** 终端时间 */
    @Indexed
    private LocalDateTime deviceTime;

    /** 服务器接收时间 */
    private LocalDateTime receivedAt;

    /** 报警标志 */
    private int warnBit;

    /** 状态标志（解码后） */
    private StatusBits status;

    /** 纬度（原始值，实际值 = latitude / 1000000.0） */
    private int latitude;

    /** 经度（原始值，实际值 = longitude / 1000000.0） */
    private int longitude;

    /** 纬度（度，保留6位小数） */
    private double lat;

    /** 经度（度，保留6位小数） */
    private double lng;

    /** 高程（米） */
    private int altitude;

    /** 速度（1/10 km/h） */
    private int speed;

    /** 方向（0~359，正北为0，顺时针） */
    private int direction;

    /** 位置附加信息 */
    private Map<Integer, Object> attributes;

    public static LocationRecord from(T0200 msg) {
        return new LocationRecord()
                .setClientId(msg.getClientId())
                .setDeviceTime(msg.getDeviceTime())
                .setReceivedAt(LocalDateTime.now())
                .setWarnBit(msg.getWarnBit())
                .setStatus(StatusBits.from(msg.getStatusBit()))
                .setLatitude(msg.getLatitude())
                .setLongitude(msg.getLongitude())
                .setLat(msg.getLat())
                .setLng(msg.getLng())
                .setAltitude(msg.getAltitude())
                .setSpeed(msg.getSpeed())
                .setDirection(msg.getDirection())
                .setAttributes(msg.getAttributes());
    }
}
