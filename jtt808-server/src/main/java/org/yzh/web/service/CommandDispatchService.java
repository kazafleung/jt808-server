package org.yzh.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.web.model.entity.DeviceCommand;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Watches the {@code device_commands} MongoDB collection via a change stream.
 * <p>
 * When a document with {@code status: "pending"} is inserted, this service:
 * <ol>
 *   <li>Checks whether the target device is connected to <em>this</em> instance.</li>
 *   <li>Atomically claims the command ({@code pending → processing}) to prevent
 *       double execution across multiple running instances.</li>
 *   <li>Deserializes the payload and dispatches the JT808 command over the
 *       device's Netty session.</li>
 *   <li>Writes the result or error back to the document ({@code done / failed}).</li>
 * </ol>
 * <p>
 * Insert schema expected from the calling application:
 * <pre>
 * {
 *   "clientId":      "013912345678",
 *   "messageClass":  "org.yzh.protocol.t808.T8201",
 *   "responseClass": "org.yzh.protocol.t808.T0201_0500",  // omit for fire-and-forget
 *   "payload":       { ... JTMessage fields ... },
 *   "status":        "pending",
 *   "createdAt":     ISODate("...")
 * }
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandDispatchService implements SmartLifecycle {

    /** Only classes within these packages may be instantiated from command documents. */
    private static final List<String> ALLOWED_PACKAGES = List.of(
            "org.yzh.protocol.t808.",
            "org.yzh.protocol.t1078.",
            "org.yzh.protocol.jsatl12.",
            "org.yzh.protocol.basics."
    );

    private static final String COLLECTION = "device_commands";
    private static final int DEVICE_RESPONSE_TIMEOUT_SECONDS = 10;
    private static final long RECONNECT_DELAY_MS = 5_000;

    private final MongoTemplate mongoTemplate;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;

    private volatile boolean running = false;
    private volatile MongoCursor<?> activeCursor;
    private Thread dispatchThread;

    // -------------------------------------------------------------------------
    // SmartLifecycle
    // -------------------------------------------------------------------------

    @Override
    public void start() {
        running = true;
        dispatchThread = new Thread(this::watchLoop, "cmd-dispatch");
        dispatchThread.setDaemon(true);
        dispatchThread.start();
        log.info("CommandDispatchService started — listening on {}", COLLECTION);
    }

    @Override
    public void stop() {
        running = false;
        MongoCursor<?> cursor = activeCursor;
        if (cursor != null) {
            try {
                cursor.close();
            } catch (Exception ignored) {
            }
        }
        if (dispatchThread != null) {
            dispatchThread.interrupt();
        }
        log.info("CommandDispatchService stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // -------------------------------------------------------------------------
    // Change stream loop
    // -------------------------------------------------------------------------

    private void watchLoop() {
        while (running) {
            try {
                openAndDrain();
            } catch (Exception e) {
                if (running) {
                    log.error("Change stream error on '{}', reconnecting in {}ms", COLLECTION, RECONNECT_DELAY_MS, e);
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void openAndDrain() {
        List<Bson> pipeline = List.of(
                Aggregates.match(Filters.and(
                        Filters.eq("operationType", "insert"),
                        Filters.eq("fullDocument.status", "pending")
                ))
        );

        try (MongoCursor<ChangeStreamDocument<Document>> cursor =
                     mongoTemplate.getCollection(COLLECTION)
                             .watch(pipeline)
                             .fullDocument(FullDocument.DEFAULT)
                             .iterator()) {

            activeCursor = cursor;
            log.info("Change stream cursor opened on '{}'", COLLECTION);

            while (running && cursor.hasNext()) {
                ChangeStreamDocument<Document> event = cursor.next();
                Document fullDoc = event.getFullDocument();
                if (fullDoc != null) {
                    DeviceCommand command = mongoTemplate.getConverter()
                            .read(DeviceCommand.class, fullDoc);
                    processCommand(command);
                }
            }
        } finally {
            activeCursor = null;
        }
    }

    // -------------------------------------------------------------------------
    // Command processing
    // -------------------------------------------------------------------------

    private void processCommand(DeviceCommand command) {
        String clientId = command.getClientId();

        // Fast path — device not on this instance, skip without touching the DB
        Session session = sessionManager.get(clientId);
        if (session == null) {
            return;
        }

        // Atomically claim: only one instance can win this findAndModify
        DeviceCommand claimed = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(command.getId())
                        .and("status").is("pending")),
                new Update().set("status", "processing"),
                FindAndModifyOptions.options().returnNew(false),
                DeviceCommand.class
        );
        if (claimed == null) {
            // Another instance claimed it first (race condition guard)
            return;
        }

        try {
            Class<? extends JTMessage> msgClass = resolveClass(command.getMessageClass());
            JTMessage request = objectMapper.convertValue(command.getPayload(), msgClass);
            request.setClientId(clientId);

            String responseClassName = command.getResponseClass();
            if (responseClassName == null || responseClassName.isBlank()) {
                // Fire-and-forget — no reply expected from device
                session.notify(request)
                        .timeout(Duration.ofSeconds(DEVICE_RESPONSE_TIMEOUT_SECONDS))
                        .subscribe(
                                v -> markDone(command.getId(), null),
                                (Throwable err) -> markFailed(command.getId(), err.getMessage())
                        );
            } else {
                Class<?> respClass = resolveClass(responseClassName);
                dispatchRequest(session, request, respClass, command.getId());
            }
        } catch (Exception e) {
            log.error("Failed to dispatch command id={} clientId={}", command.getId(), clientId, e);
            markFailed(command.getId(), e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatchRequest(Session session, JTMessage request, Class<?> respClass, String commandId) {
        Mono<Object> mono = (Mono) session.request(request, (Class) respClass);
        mono.timeout(Duration.ofSeconds(DEVICE_RESPONSE_TIMEOUT_SECONDS))
                .subscribe(
                        resp -> {
                            Map<String, Object> resultMap = objectMapper.convertValue(resp, Map.class);
                            markDone(commandId, resultMap);
                        },
                        err -> markFailed(commandId, err.getMessage())
                );
    }

    // -------------------------------------------------------------------------
    // Status updates
    // -------------------------------------------------------------------------

    private void markDone(String id, Map<String, Object> result) {
        Update update = new Update()
                .set("status", "done")
                .set("processedAt", LocalDateTime.now());
        if (result != null) {
            update.set("result", result);
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), update, DeviceCommand.class);
    }

    private void markFailed(String id, String error) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("status", "failed")
                        .set("error", error)
                        .set("processedAt", LocalDateTime.now()),
                DeviceCommand.class
        );
    }

    // -------------------------------------------------------------------------
    // Class resolution (security: restricted to protocol packages)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> Class<T> resolveClass(String className) throws ClassNotFoundException {
        boolean allowed = ALLOWED_PACKAGES.stream().anyMatch(className::startsWith);
        if (!allowed) {
            throw new SecurityException(
                    "Refused to load class '" + className + "': not in an allowed protocol package");
        }
        return (Class<T>) Class.forName(className);
    }
}
