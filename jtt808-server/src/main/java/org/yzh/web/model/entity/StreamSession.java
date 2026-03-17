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
import java.time.ZoneOffset;

/**
 * Tracks live streaming sessions initiated via JT/T 1078 T9101 commands.
 * One document per (clientId, channelNo) — upserted on each T9101 request.
 * Status is updated both by this server (on T9102 control commands) and by
 * the media server when it receives/loses stream data.
 */
@Data
@Accessors(chain = true)
@Document(collection = "stream_sessions")
@CompoundIndexes({
        @CompoundIndex(name = "clientId_channelNo", def = "{'clientId': 1, 'channelNo': 1}", unique = true)
})
public class StreamSession {

    @Id
    private String id;

    /** 媒体服务器标识: 12位clientId(左补0) + "-" + channelNo */
    @Indexed(unique = true)
    private String tag;

    @Schema(description = "终端手机号")
    private String clientId;

    @Schema(description = "逻辑通道号")
    private int channelNo;

    @Schema(description = "媒体类型: 0=音视频 1=视频 2=双向对讲 3=监听 4=中心广播 5=透传")
    private int mediaType;

    @Schema(description = "码流类型: 0=主码流 1=子码流")
    private int streamType;

    @Schema(description = "媒体服务器IP")
    private String serverIp;

    @Schema(description = "媒体服务器TCP端口")
    private int serverTcpPort;

    @Schema(description = "媒体服务器UDP端口")
    private int serverUdpPort;

    @Schema(description = "流状态")
    private Status status;

    @Schema(description = "请求时间 (UTC)")
    private LocalDateTime requestedAt;

    @Schema(description = "最后状态更新时间 (UTC)")
    private LocalDateTime updatedAt;

    public enum Status {
        /** T9101 sent, waiting for device to connect to media server */
        REQUESTED,
        /** Media server confirmed stream is active */
        STREAMING,
        /** T9102 command=2: stream paused */
        PAUSED,
        /** T9102 command=0/4: stream closed, or media server lost the stream */
        STOPPED
    }

    public StreamSession markUpdated() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        return this;
    }

    public static String buildTag(String clientId, int channelNo) {
        return String.format("%012d", Long.parseLong(clientId)) + "-" + channelNo;
    }
}
