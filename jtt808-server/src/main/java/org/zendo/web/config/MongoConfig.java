package org.zendo.web.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

/**
 * Removes the {@code _class} type field written by Spring Data MongoDB into
 * every document.
 * The {@code _class} field is only needed for polymorphic deserialization; none
 * of the
 * collections in this application store mixed subtypes, so it just wastes
 * space.
 */
@Configuration
public class MongoConfig {

    private final MappingMongoConverter converter;

    public MongoConfig(MappingMongoConverter converter) {
        this.converter = converter;
    }

    @PostConstruct
    void disableTypeKey() {
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
    }
}
