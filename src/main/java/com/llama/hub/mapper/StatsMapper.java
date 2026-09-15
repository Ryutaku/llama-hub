package com.llama.hub.mapper;

import com.llama.hub.model.DashboardInfo;
import com.llama.hub.model.TrendRowVO;
import com.llama.hub.model.TodayStatsVO;
import com.llama.hub.model.UsageRowVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 仪表盘 / 用量统计聚合查询（SQL 见 mybatis/StatsMapper.xml） */
@Mapper
public interface StatsMapper {

    TodayStatsVO todayStats(@Param("start") LocalDateTime start);

    long countActiveKeys(@Param("start") LocalDateTime start);

    List<TrendRowVO> trendByDay(@Param("start") LocalDateTime start);

    List<DashboardInfo.TopKey> topKeys(@Param("start") LocalDateTime start);

    List<UsageRowVO> usageByKey(@Param("keyId") Long keyId,
                                @Param("start") LocalDateTime start,
                                @Param("end") LocalDateTime end);
}
