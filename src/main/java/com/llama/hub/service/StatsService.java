package com.llama.hub.service;

import com.llama.hub.mapper.StatsMapper;
import com.llama.hub.model.DashboardInfo;
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
}
