package org.yzh.web.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
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

    @Schema(description = "机构id")
    @Field("agId")
    protected int agencyId;

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