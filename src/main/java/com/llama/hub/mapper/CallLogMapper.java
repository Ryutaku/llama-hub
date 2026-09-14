package com.llama.hub.mapper;

import com.llama.hub.model.CallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CallLogMapper {

    void insert(CallLog callLog);

    List<CallLog> selectList(@Param("keyId") Long keyId,
                             @Param("startAt") LocalDateTime startAt,
                             @Param("endAt") LocalDateTime endAt);

    long count(@Param("keyId") Long keyId,
               @Param("startAt") LocalDateTime startAt,
               @Param("endAt") LocalDateTime endAt);

    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
