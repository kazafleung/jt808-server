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
import org.springframework.data.mongodb.core.mapping.Field;
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
                @CompoundIndex(name = "clientId_deviceTime", def = "{'cid': 1, 'dt': -1}")
})
public class LocationRecord {

        @Id
        private String id;

        /** 终端手机号 (device phone number / client ID) */
        @Indexed
        @Field("cid")
        private String clientId;

        /** 终端时间 */
        @Indexed
        @Field("dt")
        private LocalDateTime deviceTime;

        /** 服务器接收时间 */
        @Field("rat")
        private LocalDateTime receivedAt;

        /** 报警标志（原始值） */
        @Field("wb")
        private int warnBit;

        /** 状态标志（原始值） */
        @Field("sb")
        private int statusBit;

        /**
         * GeoJSON Point (coordinates: [longitude, latitude]) — supports 2dsphere geo
         * queries
         */
        @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
        @Field("loc")
        private GeoJsonPoint location;

        /** 高程（米） */
        @Field("alt")
        private int altitude;

        /** 速度（1/10 km/h） */
        @Field("spd")
        private int speed;

        /** 方向（0~359，正北为0，顺时针） */
        @Field("dir")
        private int direction;

        /** 位置附加信息 */
        @Field("attr")
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
