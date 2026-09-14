package com.llama.hub.mapper;

import com.llama.hub.model.ModelConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ModelConfigMapper {

    ModelConfig findRow(@Param("id") Long id);

    void insertRow(ModelConfig row);

    int updateRow(ModelConfig row);
}
