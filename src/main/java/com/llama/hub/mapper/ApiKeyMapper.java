package com.llama.hub.mapper;

import com.llama.hub.model.ApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApiKeyMapper {

    void insert(ApiKey key);

    ApiKey findById(@Param("id") Long id);

    ApiKey findByKeyHash(@Param("keyHash") String keyHash);

    List<ApiKey> findAll();

    int update(ApiKey key);

    int deleteById(@Param("id") Long id);
}
