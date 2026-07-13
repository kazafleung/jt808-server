package org.zendo.web.service;

import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.zendo.web.model.entity.Device;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyDeviceDiagnosticsCleanup {

    private static final String[] LEGACY_FIELDS = {
            "dg", "ds", "dw", "ol", "ml", "dl", "df", "ao"
    };

    private final MongoTemplate mongoTemplate;

    /** Removes rolling counters left by versions that predate daily aggregation. */
    @EventListener(ApplicationReadyEvent.class)
    public void removeLegacyCounters() {
        Criteria[] exists = new Criteria[LEGACY_FIELDS.length];
        Update unset = new Update();
        for (int i = 0; i < LEGACY_FIELDS.length; i++) {
            exists[i] = Criteria.where(LEGACY_FIELDS[i]).exists(true);
            unset.unset(LEGACY_FIELDS[i]);
        }

        UpdateResult result = mongoTemplate.updateMulti(
                Query.query(new Criteria().orOperator(exists)),
                unset,
                Device.class);
        if (result.getModifiedCount() > 0) {
            log.info("Removed legacy embedded diagnostic counters from {} device records",
                    result.getModifiedCount());
        }
    }
}
