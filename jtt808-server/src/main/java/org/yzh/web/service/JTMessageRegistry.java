package org.yzh.web.service;

import io.github.yezhihao.protostar.annotation.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t808.*;
import org.yzh.web.config.JTProperties;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Scans the protocol package at startup and builds a registry of
 * JT808 message ID → JTMessage subclass, driven by {@code @Message} annotations.
 * <p>
 * This allows command documents to specify only {@code messageId} (e.g. {@code "T8300"})
 * without needing to know the fully qualified class name.
 */
@Slf4j
@Component
public class JTMessageRegistry {

    /** messageId (int) → concrete JTMessage subclass */
    private final Map<Integer, Class<? extends JTMessage>> registry;

    /**
     * Maps a downlink command message ID to the expected uplink response class.
     * Commands not listed here default to {@link T0001} (terminal general response).
     */
    private static final Map<Integer, Class<?>> RESPONSE_MAP;

    static {
        Map<Integer, Class<?>> m = new HashMap<>();
        m.put(JT808.查询终端参数,         T0104.class);   // 0x8104
        m.put(JT808.查询指定终端参数,     T0104.class);   // 0x8106
        m.put(JT808.查询终端属性,         T0107.class);   // 0x8107
        m.put(JT808.位置信息查询,         T0201_0500.class); // 0x8201
        m.put(JT808.车辆控制,             T0201_0500.class); // 0x8500
        m.put(JT808.查询区域或线路数据,   T0608.class);   // 0x8608
        m.put(JT808.上报驾驶员身份信息请求, T0702.class); // 0x8702
        m.put(JT808.摄像头立即拍摄命令,   T0805.class);  // 0x8801
        m.put(JT808.存储多媒体数据检索,   T0802.class);  // 0x8802
        RESPONSE_MAP = Collections.unmodifiableMap(m);
    }

    @SuppressWarnings("unchecked")
    public JTMessageRegistry(JTProperties jtProperties) {
        Map<Integer, Class<? extends JTMessage>> map = new HashMap<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Message.class));

        String basePackage = jtProperties.getMessagePackage();
        for (var bd : scanner.findCandidateComponents(basePackage)) {
            try {
                Class<?> cls = Class.forName(bd.getBeanClassName());
                if (!JTMessage.class.isAssignableFrom(cls)) continue;

                Message annotation = cls.getAnnotation(Message.class);
                if (annotation == null) continue;

                for (int id : annotation.value()) {
                    Class<? extends JTMessage> prev = map.put(id, (Class<? extends JTMessage>) cls);
                    if (prev != null && prev != cls) {
                        // Multiple classes mapped to same ID (e.g. T9302 with several IDs) — keep first
                        log.debug("MessageId 0x{} already mapped to {}, ignoring {}", Integer.toHexString(id), prev.getSimpleName(), cls.getSimpleName());
                        map.put(id, prev);
                    }
                }
            } catch (ClassNotFoundException e) {
                log.warn("Could not load protocol class {}", bd.getBeanClassName(), e);
            }
        }

        this.registry = Collections.unmodifiableMap(map);
        log.info("JTMessageRegistry loaded {} message types from {}", registry.size(), basePackage);
    }

    /**
     * Returns the JTMessage subclass for the given message ID, or {@code JTMessage.class}
     * if no typed subclass is registered (bare command, messageId only needed).
     */
    public Class<? extends JTMessage> resolve(int messageId) {
        return registry.getOrDefault(messageId, JTMessage.class);
    }

    /**
     * Returns the expected response class for a given downlink command message ID.
     * Defaults to {@link T0001} (terminal general response) for unlisted commands.
     */
    public Class<?> resolveResponse(int messageId) {
        return RESPONSE_MAP.getOrDefault(messageId, T0001.class);
    }
}
