package com.llama.hub.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SchemaMapper {

    /** 幂等建表（CREATE ... IF NOT EXISTS），启动时执行一次。 */
    void init();
}
