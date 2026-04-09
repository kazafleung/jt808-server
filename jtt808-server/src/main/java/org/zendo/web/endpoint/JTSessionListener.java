package org.zendo.web.endpoint;

import io.github.yezhihao.netmc.core.model.Message;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zendo.protocol.basics.JTMessage;
import org.zendo.web.model.entity.Device;
import org.zendo.web.model.enums.SessionKey;
import org.zendo.web.service.DeviceService;

import java.util.function.BiConsumer;

/**
 * @author yezhihao
 *         https://gitee.com/yezhihao/jt808-server
 */
@Component
public class JTSessionListener implements SessionListener {

    private final DeviceService deviceService;
    private final String instanceUrl;

    public JTSessionListener(DeviceService deviceService,
            @Value("${instance.url}") String instanceUrl) {
        this.deviceService = deviceService;
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
        deviceService.setInstanceUrl(session.getClientId(), instanceUrl);
    }

    /**
     * 设备离线 — clear the instance URL so the app server knows the device is offline.
     */
    @Override
    public void sessionDestroyed(Session session) {
        deviceService.setInstanceUrl(session.getClientId(), null);
    }
}