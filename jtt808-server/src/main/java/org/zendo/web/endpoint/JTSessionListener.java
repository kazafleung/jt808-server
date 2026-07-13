package org.zendo.web.endpoint;

import io.github.yezhihao.netmc.core.model.Message;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zendo.protocol.basics.JTMessage;
import org.zendo.web.model.entity.Device;
import org.zendo.web.model.enums.SessionKey;
import org.zendo.web.service.DeviceFileRequestWatchService;
import org.zendo.web.service.DeviceService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * @author yezhihao
 *         https://gitee.com/yezhihao/jt808-server
 */
@Component
public class JTSessionListener implements SessionListener {

    private final DeviceService deviceService;
    private final DeviceFileRequestWatchService fileRequestWatchService;
    private final String instanceUrl;

    public JTSessionListener(DeviceService deviceService,
            DeviceFileRequestWatchService fileRequestWatchService,
            @Value("${instance.url}") String instanceUrl) {
        this.deviceService = deviceService;
        this.fileRequestWatchService = fileRequestWatchService;
        this.instanceUrl = instanceUrl;
    }

    /**
     * 下行消息拦截器
     */
    private static final BiConsumer<Session, Message> requestInterceptor = (session, message) -> {
        JTMessage request = (JTMessage) message;
        request.setClientId(session.getClientId());
        request.setSerialNo(session.nextSerialNo());

        if (request.getMessageId() == 0) {
            request.setMessageId(request.reflectMessageId());
        }

        Device device = session.getAttribute(SessionKey.Device);
        if (device != null) {
            int protocolVersion = device.getProtocolVersion();
            if (protocolVersion > 0) {
                request.setVersion(true);
                request.setProtocolVersion(protocolVersion);
            }
        }
    };

    /**
     * 设备连接
     */
    @Override
    public void sessionCreated(Session session) {
        session.requestInterceptor(requestInterceptor);
    }

    /**
     * 设备注册 — record this instance as the owner of the device's TCP session.
     */
    @Override
    public void sessionRegistered(Session session) {
        LocalDateTime onlineAt = LocalDateTime.now(ZoneOffset.UTC);
        String sessionEventId = sessionEventId(session);
        deviceService.setInstanceUrl(session.getClientId(), instanceUrl, sessionEventId, onlineAt);
        session.setAttribute(SessionKey.OnlineAt, onlineAt);
        // Run asynchronously with a short delay so the T8003 auth ACK is fully
        // sent to the device before we attempt to dispatch pending commands.
        // Sending commands synchronously during sessionRegistered would queue
        // them in Netty before the auth ACK, causing the device to ignore them.
        String cid = session.getClientId();
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            fileRequestWatchService.processPendingRequests(cid);
        });
    }

    /**
     * 设备离线 — clear the instance URL and accumulate online duration for the day.
     */
    @Override
    public void sessionDestroyed(Session session) {
        LocalDateTime onlineAt = session.getAttribute(SessionKey.OnlineAt);
        LocalDateTime offlineAt = LocalDateTime.now(ZoneOffset.UTC);
        String sessionEventId = sessionEventId(session);
        deviceService.setInstanceUrl(session.getClientId(), null, sessionEventId, offlineAt);
        if (onlineAt != null) {
            deviceService.recordSessionEnd(session.getClientId(), sessionEventId, onlineAt, offlineAt);
        }
    }

    private static String sessionEventId(Session session) {
        return session.getClientId() + ':' + session.getCreationTime();
    }
}
