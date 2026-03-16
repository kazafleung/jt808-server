package org.yzh.web.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document for JT808 T0801 multimedia uploads.
 * Stores metadata and the OCI object store path for each uploaded file.
 */
@Data
@Accessors(chain = true)
@Document(collection = "media_records")
@CompoundIndexes({
        @CompoundIndex(name = "clientId_deviceTime", def = "{'clientId': 1, 'deviceTime': -1}")
})
public class MediaRecord {

    @Id
    private String id;

    @Schema(description = "终端手机号")
    @Indexed
    private String clientId;

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "多媒体数据ID (from T0801)")
    private int mediaId;

    @Schema(description = "通道ID")
    private int channelId;

    @Schema(description = "多媒体类型: 0=图像 1=音频 2=视频")
    private int mediaType;

    @Schema(description = "多媒体格式: 0=JPEG 1=TIF 2=MP3 3=WAV 4=WMV")
    private int format;

    @Schema(description = "事件项编码")
    private int event;

    @Schema(description = "设备时间 (UTC)")
    @Indexed
    private LocalDateTime deviceTime;

    @Schema(description = "服务器接收时间 (UTC)")
    private LocalDateTime receivedAt;

    @Schema(description = "OCI对象存储路径")
    private String objectStorePath;
}
