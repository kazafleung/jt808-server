package org.yzh.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.yzh.protocol.t808.T0200;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

        /** 报警标志（原始值） */
        private int warnBit;

        /** 状态标志（原始值） */
        private int statusBit;

        /**
         * GeoJSON Point (coordinates: [longitude, latitude]) — supports 2dsphere geo
         * queries
         */
        @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
        private GeoJsonPoint location;

        /** 高程（米） */
        private int altitude;

        /** 速度（1/10 km/h） */
        private int speed;

        /** 方向（0~359，正北为0，顺时针） */
        private int direction;

        /** 位置附加信息 */
        private Map<Integer, Object> attributes;

        public static LocationRecord from(T0200 msg) {
                // deviceTime from JT808 is always CST (UTC+8); convert to UTC for storage
                LocalDateTime deviceTimeUtc = msg.getDeviceTime() == null ? null
                                : msg.getDeviceTime().atZone(ZoneId.of("Asia/Hong_Kong"))
                                                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                return new LocationRecord()
                                .setClientId(msg.getClientId())
                                .setDeviceTime(deviceTimeUtc)
                                .setReceivedAt(LocalDateTime.now(ZoneOffset.UTC))
                                .setWarnBit(msg.getWarnBit())
                                .setStatusBit(msg.getStatusBit())
                                .setLocation(new GeoJsonPoint(msg.getLng(), msg.getLat()))
                                .setAltitude(msg.getAltitude())
                                .setSpeed(msg.getSpeed())
                                .setDirection(msg.getDirection())
                                .setAttributes(msg.getAttributes());
        }
}
