package com.llama.hub.config;

import lombok.extern.slf4j.Slf4j;
import com.llama.hub.mapper.SchemaMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时执行幂等建表（SQL 在 mybatis/SchemaMapper.xml）。 */
@Component
@Slf4j
public class SchemaInitializer {


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
