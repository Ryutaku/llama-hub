package com.gateway.repository;

import com.gateway.model.CallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CallLogRepository extends JpaRepository<CallLog, Long>, JpaSpecificationExecutor<CallLog> {

    @Modifying(clearAutomatically = true)
    @Query("delete from CallLog c where c.startedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
