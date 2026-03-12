package org.yzh.web.model.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Represents a command dispatched to a JT808 device via MongoDB change stream.
 * <p>
 * Inserted by an external application with {@code status: "pending"}.
 * This server atomically claims and executes the command on the instance
 * that holds the active device session.
 * <p>
 * Required fields (set by inserting application):
 * <ul>
 *   <li>{@code clientId}     – device BCD mobile number (e.g. "013912345678") — matches the SessionManager key</li>
 *   <li>{@code messageClass} – fully qualified JTMessage subclass name (e.g. "org.yzh.protocol.t808.T8201")</li>
 *   <li>{@code responseClass}– fully qualified response class name, or null for fire-and-forget (e.g. "org.yzh.protocol.t808.T0201_0500")</li>
 *   <li>{@code payload}      – field map matching the JTMessage subclass (clientId is injected automatically)</li>
 *   <li>{@code status}       – must be "pending" to be picked up</li>
 * </ul>
 */
@Data
@Document(collection = "device_commands")
public class DeviceCommand {

    @Id
    private String id;

    /** BCD mobile number — used as the SessionManager key. Matches {@code JTMessage.clientId} and {@code Device.mobileNo}. */
    @Indexed
    private String clientId;

    /**
     * Fully qualified class name of the JTMessage subclass to send.
     * Must be in the {@code org.yzh.protocol} package tree.
     * Example: {@code "org.yzh.protocol.t808.T8201"}
     */
    private String messageClass;

    /**
     * Fully qualified class name of the expected response type.
     * Set to null or omit for fire-and-forget (notify-only) commands.
     * Example: {@code "org.yzh.protocol.t808.T0201_0500"}
     */
    private String responseClass;

    /**
     * JSON field map representing the JTMessage request body.
     * The {@code clientId} field is injected automatically from {@code deviceId};
     * all other protocol fields should match the target {@code messageClass}.
     */
    private Map<String, Object> payload;

    /**
     * Lifecycle status:
     * <ul>
     *   <li>{@code pending}    – waiting to be picked up</li>
     *   <li>{@code processing} – claimed by an instance, awaiting device response</li>
     *   <li>{@code done}       – device responded successfully</li>
     *   <li>{@code failed}     – send error, timeout, or device offline</li>
     * </ul>
     */
    private String status;

    /** Serialized device response, populated on {@code done}. */
    private Map<String, Object> result;

    /** Error message, populated on {@code failed}. */
    private String error;

    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}
