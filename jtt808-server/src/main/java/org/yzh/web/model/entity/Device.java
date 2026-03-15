package org.yzh.web.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

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
    private String deviceId;
    @Schema(description = "设备手机号")
    @Indexed(unique = true)
    private String mobileNo;

    @Schema(description = "车牌号")
    private String plateNo;

    @Schema(description = "机构id")
    protected int agencyId;

    @Schema(description = "司机id(人脸识别)")
    protected int driverId;

    @Schema(description = "协议版本号")
    private int protocolVersion;

    @Schema(description = "首次注册时间")
    private LocalDateTime registeredAt;

    @Schema(description = "最新位置")
    private LocationRecord location;

    public void updateLocation(LocationRecord location) {
        if (this.location == null) {
            this.location = location;
        } else if (this.location.getDeviceTime().isBefore(location.getDeviceTime())) {
            this.location = location;
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