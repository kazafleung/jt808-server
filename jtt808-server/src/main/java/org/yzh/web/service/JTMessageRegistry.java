package org.yzh.web.service;

import io.github.yezhihao.protostar.annotation.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.yzh.protocol.basics.JTMessage;
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
}
