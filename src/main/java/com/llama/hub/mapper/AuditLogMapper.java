package com.llama.hub.mapper;

import com.llama.hub.model.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuditLogMapper {

    void insert(AuditLog log);

    List<AuditLog> selectList(@Param("username") String username,
                              @Param("action") String action,
                              @Param("startAt") LocalDateTime startAt,
                              @Param("endAt") LocalDateTime endAt);

    long count(@Param("username") String username,
               @Param("action") String action,
               @Param("startAt") LocalDateTime startAt,
               @Param("endAt") LocalDateTime endAt);

    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
