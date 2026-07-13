package org.zendo.web.service;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.zendo.web.model.entity.Device;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyDeviceDiagnosticsCleanupTest {

    @Test
    void removesEveryLegacyCounterFromMatchingDevices() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(2L);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(Device.class)))
                .thenReturn(updateResult);

        new LegacyDeviceDiagnosticsCleanup(mongoTemplate).removeLegacyCounters();

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(queryCaptor.capture(), updateCaptor.capture(), eq(Device.class));

        List<?> alternatives = queryCaptor.getValue().getQueryObject().getList("$or", Object.class);
        assertEquals(8, alternatives.size());

        Document unset = updateCaptor.getValue().getUpdateObject().get("$unset", Document.class);
        assertEquals(Set.of("dg", "ds", "dw", "ol", "ml", "dl", "df", "ao"), unset.keySet());
    }
}
