package org.zendo.web.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * Represents a platform-initiated file retrieval request targeting a device.
 * <p>
 * Supported types:
 * <ul>
 * <li>{@code log} — device log upload (T8700)</li>
 * <li>{@code image} — stored image upload (T8803 type=0)</li>
 * <li>{@code video} — stored video upload (T8803 type=2)</li>
 * </ul>
 * The watch service picks up newly-inserted documents whose {@code cid} matches
 * a device currently connected to this server instance and dispatches the
 * appropriate JT808 command.
 */
@Data
@Accessors(chain = true)
@Document(collection = "devicefilerequests")
public class DeviceFileRequest {

    @Id
    private String id;

    @Schema(description = "终端手机号 (clientId)")
    @Indexed
    @Field("cid")
    private String cid;

    @Schema(description = "设备ObjectId引用")
    @Indexed
    private String deviceId;

    @Schema(description = "请求类型: LOG | IMAGE | VIDEO")
    private Type type;

    @Schema(description = "状态: PENDING | REQUESTED | COMPLETED | FAILED")
    private Status status = Status.PENDING;

    @Schema(description = "失败原因")
    private String failReason;

    @Schema(description = "流水号 (平台下发指令序列号)")
    private String serialNo;

    @Schema(description = "通道ID")
    private Integer channel;

    @Schema(description = "起始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "文件URL (完成后填入)")
    private String url;

    @Schema(description = "命令下发时间")
    private LocalDateTime requestedAt;

    @Schema(description = "完成时间")
    private LocalDateTime completedAt;

    @CreatedDate
    @Field("createdAt")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Field("updatedAt")
    private LocalDateTime updatedAt;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum Type {
        LOG, IMAGE, VIDEO
    }

    public enum Status {
        PENDING, REQUESTED, COMPLETED, FAILED
    }
}
