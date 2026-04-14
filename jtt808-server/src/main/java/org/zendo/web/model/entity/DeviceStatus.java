package org.zendo.web.model.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.springframework.data.mongodb.core.mapping.Field;
import org.zendo.protocol.commons.transform.AttributeKey;
import org.zendo.protocol.commons.transform.attribute.InOutAreaAlarm;
import org.zendo.protocol.commons.transform.attribute.OverSpeedAlarm;
import org.zendo.protocol.commons.transform.attribute.RouteDriveTimeAlarm;
import org.zendo.protocol.commons.transform.attribute.TirePressure;
import org.zendo.protocol.t808.T0200;

import java.util.Map;

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

    // --- 位置附加信息（提取字段，便于查询） ---

    /** 0x01 里程, 1/10km */
    @Field("a01")
    private Long mileage;

    /** 0x02 油量, 1/10L */
    @Field("a02")
    private Integer fuel;

    /** 0x03 行驶记录速度, 1/10km/h */
    @Field("a03")
    private Integer recorderSpeed;

    /** 0x04 需人工确认报警事件ID */
    @Field("a04")
    private Integer alarmEventId;

    /** 0x05 胎压, 单位Pa, 30字节 */
    @Field("a05")
    private TirePressure tirePressure;

    /** 0x06 车厢温度, 摄氏度, -32767~+32767 */
    @Field("a06")
    private Short carriageTemperature;

    /** 0x11 超速报警附加信息 */
    @Field("a11")
    private OverSpeedAlarm overSpeedAlarm;

    /** 0x12 进出区域/路线报警附加信息 */
    @Field("a12")
    private InOutAreaAlarm inOutAreaAlarm;

    /** 0x13 路段行驶时间不足/过长报警附加信息 */
    @Field("a13")
    private RouteDriveTimeAlarm routeDriveTimeAlarm;

    /** 0x25 扩展车辆信号状态位 */
    @Field("a25")
    private Integer signal;

    /** 0x2A IO状态位 */
    @Field("a2a")
    private Integer ioState;

    /** 0x2B 模拟量 (bit0-15: AD0, bit16-31: AD1) */
    @Field("a2b")
    private Integer analogQuantity;

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
        dl.setAccOn(base.isAccOn());
        dl.setSignalStrength(base.getSignalStrength());
        dl.setGnssCount(base.getGnssCount());
        // decoded bit fields
        dl.setWarn(WarnBits.from(msg.getWarnBit()));
        dl.setStatus(StatusBits.from(msg.getStatusBit()));
        // extracted attribute fields
        Map<Integer, Object> attrs = msg.getAttributes();
        if (attrs != null) {
            dl.setMileage((Long) attrs.get(AttributeKey.Mileage));
            dl.setFuel((Integer) attrs.get(AttributeKey.Fuel));
            dl.setRecorderSpeed((Integer) attrs.get(AttributeKey.Speed));
            dl.setAlarmEventId((Integer) attrs.get(AttributeKey.AlarmEventId));
            dl.setTirePressure((TirePressure) attrs.get(AttributeKey.TirePressure));
            dl.setCarriageTemperature((Short) attrs.get(AttributeKey.CarriageTemperature));
            dl.setOverSpeedAlarm((OverSpeedAlarm) attrs.get(AttributeKey.OverSpeedAlarm));
            dl.setInOutAreaAlarm((InOutAreaAlarm) attrs.get(AttributeKey.InOutAreaAlarm));
            dl.setRouteDriveTimeAlarm((RouteDriveTimeAlarm) attrs.get(AttributeKey.RouteDriveTimeAlarm));
            dl.setSignal((Integer) attrs.get(AttributeKey.Signal));
            dl.setIoState((Integer) attrs.get(AttributeKey.IoState));
            dl.setAnalogQuantity((Integer) attrs.get(AttributeKey.AnalogQuantity));
        }
        return dl;
    }
}
