package com.llama.hub.service;

import com.llama.hub.mapper.StatsMapper;
import com.llama.hub.model.DailyStatsInfo;
import com.llama.hub.model.DashboardInfo;
import com.llama.hub.model.DayUsageRowVO;
import com.llama.hub.model.TrendRowVO;
import com.llama.hub.model.TodayStatsVO;
import com.llama.hub.model.UsageRowVO;
import com.llama.hub.model.UsageStatsInfo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 每日用量补零区间上限（天） */
    private static final int DAILY_MAX_DAYS = 366;

    private final StatsMapper statsMapper;
    private final UpstreamHealthService healthService;

    public StatsService(StatsMapper statsMapper, UpstreamHealthService healthService) {
        this.statsMapper = statsMapper;
        this.healthService = healthService;
    }

    public DashboardInfo dashboard() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        DashboardInfo m = new DashboardInfo();

        TodayStatsVO t = statsMapper.todayStats(today);
        m.setTodayRequests(t.getRequests());
        m.setTodayTokens(t.getTokens());
        m.setTodayCacheHitRate(t.getPrompt() > 0
                ? BigDecimal.valueOf(t.getCached() * 100.0 / t.getPrompt()).setScale(2, RoundingMode.HALF_UP)
                : null);
        m.setActiveKeys(statsMapper.countActiveKeys(today));

        // 近 7 天趋势（缺失日期补零）
        LocalDate start = LocalDate.now().minusDays(6);
        Map<String, TrendRowVO> byDate = new LinkedHashMap<>();
        for (TrendRowVO row : statsMapper.trendByDay(start.atStartOfDay())) {
            byDate.put(row.getD().toString(), row);
        }
        List<DashboardInfo.TrendPoint> trend = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            TrendRowVO row = byDate.get(d.toString());
            DashboardInfo.TrendPoint day = new DashboardInfo.TrendPoint();
            day.setDate(DATE_FMT.format(d));
            day.setDateFull(d.toString());
            day.setCount(row == null ? 0 : row.getCnt());
            day.setTokens(row == null ? 0 : row.getTok());
            day.setPromptTokens(row == null ? 0L : row.getPrompt());
            day.setCompletionTokens(row == null ? 0L : row.getComp());
            day.setCachedTokens(row == null ? 0L : row.getCached());
            trend.add(day);
        }
        m.setTrend(trend);

        m.setTopKeys(statsMapper.topKeys(today));
        m.setUpstream(healthService.status());
        return m;
    }

    /** 按 Key + 日期区间统计 token 用量（[keyId 为 null 时聚合所有 Key]） */
    public UsageStatsInfo usageStats(Long keyId, LocalDateTime start, LocalDateTime end) {
        List<UsageRowVO> rows = statsMapper.usageByKey(keyId, start, end);

        UsageStatsInfo.Total total = new UsageStatsInfo.Total();
        long cnt = 0, prompt = 0, completion = 0, tok = 0, cached = 0, errors = 0;
        List<UsageStatsInfo.Item> items = new ArrayList<>();
        for (UsageRowVO row : rows) {
            UsageStatsInfo.Item item = new UsageStatsInfo.Item();
            item.setKeyId(row.getKeyId());
            item.setName(row.getName());
            item.setCount(row.getCount());
            item.setPromptTokens(row.getPrompt());
            item.setCompletionTokens(row.getCompletion());
            item.setTotalTokens(row.getTotal());
            item.setCachedTokens(row.getCached());
            item.setCacheHitRate(row.getPrompt() > 0
                    ? BigDecimal.valueOf(row.getCached() * 100.0 / row.getPrompt()).setScale(2, RoundingMode.HALF_UP)
                    : null);
            item.setAvgSpeed(row.getAvgSpeed() == null ? null
                    : row.getAvgSpeed().setScale(2, RoundingMode.HALF_UP));
            item.setAvgDuration(row.getAvgDuration());
            item.setErrors(row.getErrors());
            items.add(item);

            cnt += row.getCount();
            prompt += row.getPrompt();
            completion += row.getCompletion();
            tok += row.getTotal();
            cached += row.getCached();
            errors += row.getErrors();
        }
        total.setCount(cnt);
        total.setPromptTokens(prompt);
        total.setCompletionTokens(completion);
        total.setTotalTokens(tok);
        total.setCachedTokens(cached);
        total.setCacheHitRate(prompt > 0
                ? BigDecimal.valueOf(cached * 100.0 / prompt).setScale(2, RoundingMode.HALF_UP)
                : null);

        UsageStatsInfo m = new UsageStatsInfo();
        m.setItems(items);
        m.setTotal(total);
        return m;
    }

    /** 按日聚合的每日用量（[keyId 为 null 时聚合所有 Key]，缺失日期补零，区间上限 366 天） */
    public DailyStatsInfo dailyStats(Long keyId, LocalDateTime start, LocalDateTime end) {
        LocalDate endDate = (end == null ? LocalDate.now() : end.toLocalDate());
        if (end != null && end.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
            // parseEnd 返回的是次日零点（开区间），回退到实际结束日
            endDate = endDate.minusDays(1);
        }
        LocalDate startDate = (start == null ? endDate.minusDays(29) : start.toLocalDate());
        if (startDate.isBefore(endDate.minusDays(DAILY_MAX_DAYS - 1L))) {
            startDate = endDate.minusDays(DAILY_MAX_DAYS - 1L);
        }

        List<DayUsageRowVO> rows = statsMapper.usageByDay(keyId,
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
        Map<LocalDate, DayUsageRowVO> byDate = new LinkedHashMap<>();
        for (DayUsageRowVO row : rows) {
            byDate.put(row.getD(), row);
        }

        DailyStatsInfo.Total total = new DailyStatsInfo.Total();
        List<DailyStatsInfo.Item> items = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            DayUsageRowVO row = byDate.get(d);
            DailyStatsInfo.Item item = new DailyStatsInfo.Item();
            item.setDate(ISO_DATE.format(d));
            item.setCount(row == null ? 0 : row.getCount());
            item.setPromptTokens(row == null ? 0 : row.getPrompt());
            item.setCompletionTokens(row == null ? 0 : row.getCompletion());
            item.setTotalTokens(row == null ? 0 : row.getTotal());
            item.setCachedTokens(row == null ? 0 : row.getCached());
            item.setCacheHitRate(row == null || row.getPrompt() <= 0 ? null
                    : BigDecimal.valueOf(row.getCached() * 100.0 / row.getPrompt()).setScale(2, RoundingMode.HALF_UP));
            item.setErrors(row == null ? 0 : row.getErrors());
            items.add(item);

            total.setCount(total.getCount() + item.getCount());
            total.setPromptTokens(total.getPromptTokens() + item.getPromptTokens());
            total.setCompletionTokens(total.getCompletionTokens() + item.getCompletionTokens());
            total.setTotalTokens(total.getTotalTokens() + item.getTotalTokens());
            total.setCachedTokens(total.getCachedTokens() + item.getCachedTokens());
            total.setErrors(total.getErrors() + item.getErrors());
        }
        total.setCacheHitRate(total.getPromptTokens() > 0
                ? BigDecimal.valueOf(total.getCachedTokens() * 100.0 / total.getPromptTokens()).setScale(2, RoundingMode.HALF_UP)
                : null);

        DailyStatsInfo m = new DailyStatsInfo();
        m.setItems(items);
        m.setTotal(total);
        return m;
    }
}
