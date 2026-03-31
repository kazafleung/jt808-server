package org.zendo.protocol.commons.transform;

import io.github.yezhihao.protostar.PrepareLoadStrategy;
import io.github.yezhihao.protostar.schema.MapSchema;
import io.github.yezhihao.protostar.schema.NumberSchema;
import org.zendo.protocol.commons.transform.attribute.*;

/**
 * 位置附加信息转换器(苏标)
 * 
 * @author yezhihao
 *         https://gitee.com/yezhihao/jt808-server
 */
public class AttributeConverter extends MapSchema<Number, Object> {

    public AttributeConverter() {
        super(NumberSchema.BYTE_INT, 1);
    }

    @Override
    protected void addSchemas(PrepareLoadStrategy<Number> schemaRegistry) {
        schemaRegistry
                // 0x01 里程, DWORD, 1/10km
                .addSchema(AttributeKey.Mileage, NumberSchema.DWORD_LONG)
                // 0x02 油量, WORD, 1/10L
                .addSchema(AttributeKey.Fuel, NumberSchema.WORD_INT)
                // 0x03 行驶记录速度, WORD, 1/10km/h
                .addSchema(AttributeKey.Speed, NumberSchema.WORD_INT)
                // 0x04 需人工确认报警事件ID, WORD
                .addSchema(AttributeKey.AlarmEventId, NumberSchema.WORD_INT)
                // 0x05 胎压, 30字节, 单位Pa
                .addSchema(AttributeKey.TirePressure, TirePressure.SCHEMA)
                // 0x06 车厢温度, WORD, 有符号 -32767~+32767 摄氏度
                .addSchema(AttributeKey.CarriageTemperature, NumberSchema.WORD_SHORT)

                // 0x11 超速报警附加信息, 1或5字节
                .addSchema(AttributeKey.OverSpeedAlarm, OverSpeedAlarm.SCHEMA)
                // 0x12 进出区域/路线报警附加信息, 6字节
                .addSchema(AttributeKey.InOutAreaAlarm, InOutAreaAlarm.SCHEMA)
                // 0x13 路段行驶时间不足/过长报警附加信息, 7字节
                .addSchema(AttributeKey.RouteDriveTimeAlarm, RouteDriveTimeAlarm.SCHEMA)

                // 0x25 扩展车辆信号状态位, DWORD
                .addSchema(AttributeKey.Signal, NumberSchema.DWORD_INT)
                // 0x2A IO状态位, WORD
                .addSchema(AttributeKey.IoState, NumberSchema.WORD_INT)
                // 0x2B 模拟量, DWORD (bit0-15: AD0; bit16-31: AD1)
                .addSchema(AttributeKey.AnalogQuantity, NumberSchema.DWORD_INT)
                // 0x30 无线通信网络信号强度, BYTE
                .addSchema(AttributeKey.SignalStrength, NumberSchema.BYTE_INT)
                // 0x31 GNSS定位卫星数, BYTE
                .addSchema(AttributeKey.GnssCount, NumberSchema.BYTE_INT);
    }
}