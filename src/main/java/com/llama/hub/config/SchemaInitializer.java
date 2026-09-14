package com.llama.hub.config;

import com.llama.hub.mapper.SchemaMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 启动时执行幂等建表（SQL 在 mybatis/SchemaMapper.xml）。 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final SchemaMapper schemaMapper;

    public SchemaInitializer(SchemaMapper schemaMapper) {
        this.schemaMapper = schemaMapper;
    }

    @PostConstruct
    public void init() {
        schemaMapper.init();
        log.info("Database schema initialized");
    }
}
