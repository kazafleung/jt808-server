package org.zendo.web.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Data
@Accessors(chain = true)
@Document(collection = "devices")
public class Device {

    @Id
    private String id;

    @Schema(description = "设备id")
    @Indexed
    @Field("did")
    private String deviceId;
    @Schema(description = "设备手机号")
    @Indexed(unique = true)
    @Field("mob")
    private String mobileNo;

    @Schema(description = "车牌号")
    @Field("pln")
    private String plateNo;

    @Schema(description = "司机id(人脸识别)")
    @Field("drId")
    protected int driverId;

    @Schema(description = "协议版本号")
    @Field("pv")
    private int protocolVersion;

    @Schema(description = "首次注册时间")
    @Field("rat")
    private LocalDateTime registeredAt;

    @Schema(description = "最新状态")
    @Field("st")
    private DeviceStatus status;

    @Schema(description = "持有此设备TCP连接的JT808实例地址", hidden = true)
    @Field("iurl")
    private String instanceUrl;

    @Schema(description = "是否在线")
    @Field("online")
    private boolean online;

    @Schema(description = "最近上线时间 (UTC)")
    @Field("onlineAt")
    private LocalDateTime onlineAt;

    @Schema(description = "最近离线时间 (UTC)")
    @Field("offlineAt")
    private LocalDateTime offlineAt;

    @Schema(description = "GPS质量滚动统计（卫星数）", hidden = true)
    @Field("dg")
    private DeviceDiagStat diagGps;

    @Schema(description = "信号强度滚动统计", hidden = true)
    @Field("ds")
    private DeviceDiagStat diagSig;

    @Schema(description = "告警类型滚动统计 (key=告警类型, e.g. \"v14\")", hidden = true)
    @Field("dw")
    private Map<String, DeviceDiagStat> diagAlarms;

    @Schema(description = "每日在线时长统计（秒）", hidden = true)
    @Field("ol")
    private DeviceOnlineStat diagOnline;

    @Schema(description = "每日里程统计（米）", hidden = true)
    @Field("ml")
    private DeviceMileageStat diagMileage;

    public void updateStatus(DeviceStatus status) {
        if (this.status == null) {
            this.status = status;
        } else if (this.status.getDeviceTime().isBefore(status.getDeviceTime())) {
            this.status = status;
        }
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        Device other = (Device) that;
        return Objects.equals(this.deviceId, other.deviceId);
    }

    @Override
    public int hashCode() {
        return ((deviceId == null) ? 0 : deviceId.hashCode());
    }
}