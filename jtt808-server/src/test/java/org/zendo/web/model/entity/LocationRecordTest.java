package org.zendo.web.model.entity;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.zendo.protocol.commons.transform.AttributeKey;
import org.zendo.protocol.commons.transform.attribute.OverSpeedAlarm;
import org.zendo.protocol.t808.T0200;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocationRecordTest {

    @Test
    void mongoConversionIgnoresAlarmLocationBackReference() throws Exception {
        T0200 message = new T0200()
                .setDeviceTime(LocalDateTime.of(2026, 7, 20, 12, 48, 25))
                .setLatitude(22_310_517)
                .setLongitude(114_225_926);
        message.setClientId("60321225");

        OverSpeedAlarm alarm = new OverSpeedAlarm((byte) 0, 0);
        alarm.setLocation(message);
        Map<Integer, Object> attributes = new HashMap<>();
        attributes.put(AttributeKey.OverSpeedAlarm, alarm);
        message.setAttributes(attributes);

        MongoCustomConversions conversions = MongoCustomConversions.create(
                MongoCustomConversions.MongoConverterConfigurationAdapter::useSpringDataJavaTimeCodecs);
        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        mappingContext.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(
                NoOpDbRefResolver.INSTANCE,
                mappingContext);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();

        Document result = new Document();
        assertDoesNotThrow(() -> converter.write(LocationRecord.from(message), result));

        Document persistedAlarm = result.get("attr", Document.class).get("17", Document.class);
        assertFalse(persistedAlarm.containsKey("location"));

        Document statusResult = new Document();
        assertDoesNotThrow(() -> converter.write(DeviceStatus.from(message), statusResult));
        assertFalse(statusResult.get("a11", Document.class).containsKey("location"));
    }
}
