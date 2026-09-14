package com.llama.hub.mapper;

import com.llama.hub.model.ModelPreset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ModelPresetMapper {

    List<ModelPreset> findAll();

    ModelPreset findById(@Param("id") Long id);

    ModelPreset findByName(@Param("name") String name);

    void insertRow(ModelPreset row);

    int updateRow(ModelPreset row);

    int deleteRow(@Param("id") Long id);
}
