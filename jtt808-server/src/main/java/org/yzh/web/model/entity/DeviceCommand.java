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
     * Optional — if omitted, {@code org.yzh.protocol.basics.JTMessage} is used.
     * Example: {@code "org.yzh.protocol.t808.T8300"}
     */
    private String messageClass;

    /**
     * JT808 message ID in any of these formats: {@code "T8104"}, {@code "8104"}, {@code "0x8104"}.
     * All are treated as hexadecimal. Required when {@code messageClass} is omitted or is the base
     * {@code JTMessage} type. Overrides any messageId already in {@code payload}.
     */
    private String messageId;

    /**
     * Fully qualified class name of the expected response type.
     * Optional — automatically resolved from the protocol registry based on {@code messageId}.
     * Only set this to override the default (e.g. for custom/vendor-specific message IDs).
     */
    private String responseClass;

    /**
     * When {@code true}, the command is sent as fire-and-forget (no response expected from device).
     * Defaults to {@code false}.
     */
    private boolean notify;

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
