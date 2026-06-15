package org.zendo.web.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Tracks streaming sessions initiated via JT/T 1078 commands.
 * Supports both live streaming (T9101) and video playback (T9201).
 * One document per (clientId, channelNo) — upserted on each request.
 * Status is updated both by this server (on control commands) and by
 * the media server when it receives/loses stream data.
 */
@Data
@Accessors(chain = true)
@Document(collection = "stream_sessions")
@CompoundIndexes({
        @CompoundIndex(name = "clientId_channelNo", def = "{'cid': 1, 'cho': 1}", unique = true)
})
public class StreamSession {

    @Id
    private String id;

    /** 媒体服务器标识: 12位clientId(左补0) + "-" + channelNo */
    @Indexed(unique = true)
    private String tag;

    @Schema(description = "流类型: LIVE=实时音视频 PLAYBACK=录像回放")
    @Field("stype")
    private StreamType streamType;

    @Schema(description = "终端手机号")
    @Field("cid")
    private String clientId;

    @Schema(description = "逻辑通道号")
    @Field("cho")
    private int channelNo;

    @Schema(description = "媒体类型: 0=音视频 1=视频 2=双向对讲 3=监听 4=中心广播 5=透传")
    @Field("mt")
    private int mediaType;

    @Schema(description = "码流类型: 0=主码流 1=子码流")
    @Field("sty")
    private int codeStreamType;

    // ── Playback-specific fields (only for StreamType.PLAYBACK) ──────────────

    @Schema(description = "存储器类型: 0=所有存储器 1=主存储器 2=灾备存储器 (仅回放)")
    @Field("stot")
    private Integer storageType;

    @Schema(description = "回放方式: 0=正常回放 1=快进回放 2=关键帧快退回放 3=关键帧播放 4=单帧上传 (仅回放)")
    @Field("pm")
    private Integer playbackMode;

    @Schema(description = "快进或快退倍数: 0=无效 1=1倍 2=2倍 3=4倍 4=8倍 5=16倍 (仅回放)")
    @Field("ps")
    private Integer playbackSpeed;

    @Schema(description = "开始时间 YYMMDDHHMMSS (仅回放)")
    @Field("pbst")
    private String startTime;

    @Schema(description = "结束时间 YYMMDDHHMMSS (仅回放)")
    @Field("pbet")
    private String endTime;

    // ── Server connection details ─────────────────────────────────────────────

    @Schema(description = "媒体服务器IP")
    @Field("sip")
    private String serverIp;

    @Schema(description = "媒体服务器TCP端口")
    @Field("stp")
    private int serverTcpPort;

    @Schema(description = "媒体服务器UDP端口")
    @Field("sup")
    private int serverUdpPort;

    @Schema(description = "流状态")
    @Field("st")
    private Status status;

    @Schema(description = "请求时间 (UTC)")
    @Field("reqAt")
    private LocalDateTime requestedAt;

    @Schema(description = "最后状态更新时间 (UTC)")
    @Field("upAt")
    private LocalDateTime updatedAt;

    @Schema(description = "订阅者列表")
    private List<Subscriber> subscribers;

    /** Stream type: live or playback */
    public enum StreamType {
        /** Real-time audio/video streaming (T9101/T9102) */
        LIVE,
        /** Recorded video playback (T9201/T9202) */
        PLAYBACK
    }

    /** A user who has subscribed to watch this stream. */
    @Data
    @Accessors(chain = true)
    public static class Subscriber {
        @Field("_id")
        private String id;

        @Field("userId")
        private String userId;

        @Field("subscribedAt")
        private LocalDateTime subscribedAt;
    }

    public enum Status {
        /** Waiting to send T9101/T9201 request */
        PENDING,
        /** T9101/T9201 sent, waiting for device to connect to media server */
        REQUESTED,
        /** Media server confirmed stream is active */
        STREAMING,
        /** T9102/T9202 pause command: stream paused */
        PAUSED,
        /** T9102/T9202 stop command: stream closed, or media server lost the stream */
        STOPPED,
        /** Media server reported a stream error */
        ERROR
    }

    public StreamSession markUpdated() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        return this;
    }

    public static String buildTag(String prefix, String clientId, int channelNo) {
        return prefix + "-" + String.format("%012d", Long.parseLong(clientId)) + "-" + channelNo;
    }
}
