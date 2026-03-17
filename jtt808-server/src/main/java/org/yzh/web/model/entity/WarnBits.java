package org.yzh.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.yzh.protocol.commons.Bit;

/**
 * Decoded alarm/warn bit flags from JT808 T0200 warnBit field.
 * Stored as an embedded document in MongoDB.
 */
@Data
@Accessors(chain = true)
public class WarnBits {

    /** Bit 0 — 紧急报警 (收到应答后清零) */
    private boolean emergencyAlarm;

    /** Bit 1 — 超速报警 (标志维持至报警条件解除) */
    private boolean overspeedAlarm;

    /** Bit 2 — 疲劳驾驶 (标志维持至报警条件解除) */
    private boolean fatigueDriving;

    /** Bit 3 — 危险预警 (收到应答后清零) */
    private boolean dangerWarning;

    /** Bit 4 — GNSS 模块发生故障 (标志维持至报警条件解除) */
    private boolean gnssFailure;

    /** Bit 5 — GNSS 天线未接或被剪断 (标志维持至报警条件解除) */
    private boolean gnssAntennaCut;

    /** Bit 6 — GNSS 天线短路 (标志维持至报警条件解除) */
    private boolean gnssAntennaShort;

    /** Bit 7 — 终端主电源欠压 (标志维持至报警条件解除) */
    private boolean mainPowerUndervoltage;

    /** Bit 8 — 终端主电源掉电 (标志维持至报警条件解除) */
    private boolean mainPowerFailure;

    /** Bit 9 — 终端 LCD 或显示器故障 (标志维持至报警条件解除) */
    private boolean lcdFailure;

    /** Bit 10 — TTS 模块故障 (标志维持至报警条件解除) */
    private boolean ttsFailure;

    /** Bit 11 — 摄像头故障 (标志维持至报警条件解除) */
    private boolean cameraFailure;

    /** Bit 12 — 道路运输证 IC 卡模块故障 (标志维持至报警条件解除) */
    private boolean icCardFailure;

    /** Bit 13 — 超速预警 (标志维持至报警条件解除) */
    private boolean overspeedWarning;

    /** Bit 14 — 疲劳驾驶预警 (标志维持至报警条件解除) */
    private boolean fatigueDrivingWarning;

    // Bits 15-17 reserved

    /** Bit 18 — 当天累计驾驶超时 (标志维持至报警条件解除) */
    private boolean drivingOvertime;

    /** Bit 19 — 超时停车 (标志维持至报警条件解除) */
    private boolean parkingOvertime;

    /** Bit 20 — 进出区域 (收到应答后清零) */
    private boolean inOutArea;

    /** Bit 21 — 进出路线 (收到应答后清零) */
    private boolean inOutRoute;

    /** Bit 22 — 路段行驶时间不足/过长 (收到应答后清零) */
    private boolean routeDriveTimeAlarm;

    /** Bit 23 — 路线偏离报警 (标志维持至报警条件解除) */
    private boolean routeDeviation;

    /** Bit 24 — 车辆 VSS 故障 (标志维持至报警条件解除) */
    private boolean vssFailure;

    /** Bit 25 — 车辆油量异常 (标志维持至报警条件解除) */
    private boolean fuelAbnormal;

    /** Bit 26 — 车辆被盗 (标志维持至报警条件解除) */
    private boolean vehicleStolen;

    /** Bit 27 — 车辆非法点火 (收到应答后清零) */
    private boolean illegalIgnition;

    /** Bit 28 — 车辆非法位移 (收到应答后清零) */
    private boolean illegalDisplacement;

    /** Bit 29 — 碰撞预警 (标志维持至报警条件解除) */
    private boolean collisionWarning;

    /** Bit 30 — 侧翻预警 (标志维持至报警条件解除) */
    private boolean rolloverWarning;

    /** Bit 31 — 非法开门报警 (收到应答后清零) */
    private boolean illegalDoorOpen;

    public static WarnBits from(int warnBit) {
        return new WarnBits()
                .setEmergencyAlarm(Bit.isTrue(warnBit, 0))
                .setOverspeedAlarm(Bit.isTrue(warnBit, 1))
                .setFatigueDriving(Bit.isTrue(warnBit, 2))
                .setDangerWarning(Bit.isTrue(warnBit, 3))
                .setGnssFailure(Bit.isTrue(warnBit, 4))
                .setGnssAntennaCut(Bit.isTrue(warnBit, 5))
                .setGnssAntennaShort(Bit.isTrue(warnBit, 6))
                .setMainPowerUndervoltage(Bit.isTrue(warnBit, 7))
                .setMainPowerFailure(Bit.isTrue(warnBit, 8))
                .setLcdFailure(Bit.isTrue(warnBit, 9))
                .setTtsFailure(Bit.isTrue(warnBit, 10))
                .setCameraFailure(Bit.isTrue(warnBit, 11))
                .setIcCardFailure(Bit.isTrue(warnBit, 12))
                .setOverspeedWarning(Bit.isTrue(warnBit, 13))
                .setFatigueDrivingWarning(Bit.isTrue(warnBit, 14))
                .setDrivingOvertime(Bit.isTrue(warnBit, 18))
                .setParkingOvertime(Bit.isTrue(warnBit, 19))
                .setInOutArea(Bit.isTrue(warnBit, 20))
                .setInOutRoute(Bit.isTrue(warnBit, 21))
                .setRouteDriveTimeAlarm(Bit.isTrue(warnBit, 22))
                .setRouteDeviation(Bit.isTrue(warnBit, 23))
                .setVssFailure(Bit.isTrue(warnBit, 24))
                .setFuelAbnormal(Bit.isTrue(warnBit, 25))
                .setVehicleStolen(Bit.isTrue(warnBit, 26))
                .setIllegalIgnition(Bit.isTrue(warnBit, 27))
                .setIllegalDisplacement(Bit.isTrue(warnBit, 28))
                .setCollisionWarning(Bit.isTrue(warnBit, 29))
                .setRolloverWarning(Bit.isTrue(warnBit, 30))
                .setIllegalDoorOpen(Bit.isTrue(warnBit, 31));
    }
}
