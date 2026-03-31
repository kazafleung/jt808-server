package org.zendo.web.model.entity;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.mongodb.core.mapping.Field;
import org.zendo.protocol.commons.Bit;

/**
 * Decoded status bit flags from JT808 T0200 statusBit field.
 * Stored as an embedded document in MongoDB.
 */
@Data
@Accessors(chain = true)
public class StatusBits {

    /** Bit 0 — ACC 开/关 */
    @Field("b0")
    private boolean accOn;

    /** Bit 1 — 定位状态 */
    @Field("b1")
    private boolean located;

    /** Bit 2 — 纬度方向: false=北纬, true=南纬 */
    @Field("b2")
    private boolean southLatitude;

    /** Bit 3 — 经度方向: false=东经, true=西经 */
    @Field("b3")
    private boolean westLongitude;

    /** Bit 4 — 营运状态: false=运营, true=停运 */
    @Field("b4")
    private boolean stopped;

    /** Bit 5 — 经纬度加密: false=未加密, true=已加密 */
    @Field("b5")
    private boolean encrypted;

    /**
     * Bits 8-9 — 载重状态: 0=空车, 1=半载, 2=保留, 3=满载
     */
    @Field("ls")
    private int loadStatus;

    /** Bit 10 — 车辆油路: false=正常, true=断开 */
    @Field("fc")
    private boolean fuelCut;

    /** Bit 11 — 车辆电路: false=正常, true=断开 */
    @Field("pc")
    private boolean powerCut;

    /** Bit 12 — 车门: false=解锁, true=加锁 */
    @Field("dl")
    private boolean doorLocked;

    /** Bit 13 — 门1(前门) */
    @Field("d1")
    private boolean door1Open;

    /** Bit 14 — 门2(中门) */
    @Field("d2")
    private boolean door2Open;

    /** Bit 15 — 门3(后门) */
    @Field("d3")
    private boolean door3Open;

    /** Bit 16 — 门4(驾驶席门) */
    @Field("d4")
    private boolean door4Open;

    /** Bit 17 — 门5(自定义) */
    @Field("d5")
    private boolean door5Open;

    /** Bit 18 — GPS 卫星定位 */
    @Field("gps")
    private boolean usingGps;

    /** Bit 19 — 北斗卫星定位 */
    @Field("bds")
    private boolean usingBeidou;

    /** Bit 20 — GLONASS 卫星定位 */
    @Field("glo")
    private boolean usingGlonass;

    /** Bit 21 — Galileo 卫星定位 */
    @Field("gal")
    private boolean usingGalileo;

    public static StatusBits from(int statusBit) {
        return new StatusBits()
                .setAccOn(Bit.isTrue(statusBit, 0))
                .setLocated(Bit.isTrue(statusBit, 1))
                .setSouthLatitude(Bit.isTrue(statusBit, 2))
                .setWestLongitude(Bit.isTrue(statusBit, 3))
                .setStopped(Bit.isTrue(statusBit, 4))
                .setEncrypted(Bit.isTrue(statusBit, 5))
                .setLoadStatus((statusBit >> 8) & 0x3)
                .setFuelCut(Bit.isTrue(statusBit, 10))
                .setPowerCut(Bit.isTrue(statusBit, 11))
                .setDoorLocked(Bit.isTrue(statusBit, 12))
                .setDoor1Open(Bit.isTrue(statusBit, 13))
                .setDoor2Open(Bit.isTrue(statusBit, 14))
                .setDoor3Open(Bit.isTrue(statusBit, 15))
                .setDoor4Open(Bit.isTrue(statusBit, 16))
                .setDoor5Open(Bit.isTrue(statusBit, 17))
                .setUsingGps(Bit.isTrue(statusBit, 18))
                .setUsingBeidou(Bit.isTrue(statusBit, 19))
                .setUsingGlonass(Bit.isTrue(statusBit, 20))
                .setUsingGalileo(Bit.isTrue(statusBit, 21));
    }
}
