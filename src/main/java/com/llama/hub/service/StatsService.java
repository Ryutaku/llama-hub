package com.llama.hub.service;

import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;
    private final UpstreamHealthService healthService;

    public StatsService(JdbcTemplate jdbcTemplate, UpstreamHealthService healthService) {
        this.jdbcTemplate = jdbcTemplate;
        this.healthService = healthService;
    }

    public Map<String, Object> dashboard() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        Map<String, Object> m = new LinkedHashMap<>();

        // 今日统计
        Map<String, Object> t = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS \"requests\", COALESCE(SUM(total_tokens),0) AS \"tokens\", "
                        + "COALESCE(SUM(cached_tokens),0) AS \"cached\", COALESCE(SUM(prompt_tokens),0) AS \"prompt\" "
                        + "FROM call_log WHERE started_at >= ?", today);
        long requests = ((Number) t.get("requests")).longValue();
        long tokens = ((Number) t.get("tokens")).longValue();
        long cached = ((Number) t.get("cached")).longValue();
        long prompt = ((Number) t.get("prompt")).longValue();
        m.put("todayRequests", requests);
        m.put("todayTokens", tokens);
        m.put("todayCacheHitRate", prompt > 0
                ? BigDecimal.valueOf(cached * 100.0 / prompt).setScale(2, RoundingMode.HALF_UP)
                : null);

        Long activeKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_key WHERE last_used_at >= ?", Long.class, today);
        m.put("activeKeys", activeKeys == null ? 0 : activeKeys);

        // 近 7 天趋势
        LocalDate start = LocalDate.now().minusDays(6);
        Map<String, Object[]> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT CAST(started_at AS DATE) AS \"d\", COUNT(*) AS \"cnt\", COALESCE(SUM(total_tokens),0) AS \"tok\", "
                        + "COALESCE(SUM(prompt_tokens),0) AS \"prompt\", COALESCE(SUM(completion_tokens),0) AS \"comp\", "
                        + "COALESCE(SUM(cached_tokens),0) AS \"cached\" "
                        + "FROM call_log WHERE started_at >= ? GROUP BY CAST(started_at AS DATE)", start.atStartOfDay())) {
            byDate.put(String.valueOf(row.get("d")),
                    new Object[]{ ((Number) row.get("cnt")).longValue(), ((Number) row.get("tok")).longValue(),
                            ((Number) row.get("prompt")).longValue(), ((Number) row.get("comp")).longValue(),
                            ((Number) row.get("cached")).longValue() });
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            Object[] rec = byDate.get(d.toString());
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", DATE_FMT.format(d));
            day.put("dateFull", d.toString());
            day.put("count", rec == null ? 0 : ((Long) rec[0]));
            day.put("tokens", rec == null ? 0 : ((Long) rec[1]));
            day.put("promptTokens", rec == null ? 0L : ((Long) rec[2]));
            day.put("completionTokens", rec == null ? 0L : ((Long) rec[3]));
            day.put("cachedTokens", rec == null ? 0L : ((Long) rec[4]));
            trend.add(day);
        }
        m.put("trend", trend);

        // Top 5 活跃 Key
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT cl.key_id AS \"keyId\", COALESCE(k.name, '(deleted)') AS \"name\", COUNT(*) AS \"cnt\", "
                        + "COALESCE(SUM(cl.total_tokens),0) AS \"tok\" "
                        + "FROM call_log cl LEFT JOIN api_key k ON k.id = cl.key_id "
                        + "WHERE cl.started_at >= ? GROUP BY cl.key_id, k.name ORDER BY \"cnt\" DESC LIMIT 5", today)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyId", row.get("keyId"));
            item.put("name", row.get("name"));
            item.put("count", row.get("cnt"));
            item.put("tokens", row.get("tok"));
            top.add(item);
        }
        m.put("topKeys", top);

        m.put("upstream", healthService.status());
        return m;
    }

    /** 按 Key + 日期区间统计 token 用量（[keyId 为 null 时聚合所有 Key]） */
    public Map<String, Object> usageStats(Long keyId, LocalDateTime start, LocalDateTime end) {
        StringBuilder sql = new StringBuilder(
                "SELECT cl.key_id AS \"keyId\", COALESCE(k.name, '(deleted)') AS \"name\", "
                        + "COUNT(*) AS \"count\", "
                        + "COALESCE(SUM(cl.prompt_tokens),0) AS \"prompt\", "
                        + "COALESCE(SUM(cl.completion_tokens),0) AS \"completion\", "
                        + "COALESCE(SUM(cl.total_tokens),0) AS \"total\", "
                        + "COALESCE(SUM(cl.cached_tokens),0) AS \"cached\", "
                        + "AVG(cl.tokens_per_sec) AS \"avgSpeed\", "
                        + "AVG(cl.duration_ms) AS \"avgDuration\", "
                        + "SUM(CASE WHEN cl.status_code IS NOT NULL AND cl.status_code >= 400 THEN 1 ELSE 0 END) AS \"errors\" "
                        + "FROM call_log cl LEFT JOIN api_key k ON k.id = cl.key_id WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (keyId != null && keyId > 0) {
            sql.append(" AND cl.key_id = ?");
            params.add(keyId);
        }
        if (start != null) {
            sql.append(" AND cl.started_at >= ?");
            params.add(start);
        }
        if (end != null) {
            sql.append(" AND cl.started_at < ?");
            params.add(end);
        }
        sql.append(" GROUP BY cl.key_id, k.name ORDER BY \"count\" DESC");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());

        Map<String, Object> total = new LinkedHashMap<>();
        long cnt = 0, prompt = 0, completion = 0, tok = 0, cached = 0, errors = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyId", row.get("keyId"));
            item.put("name", row.get("name"));
            item.put("count", row.get("count"));
            item.put("promptTokens", row.get("prompt"));
            item.put("completionTokens", row.get("completion"));
            item.put("totalTokens", row.get("total"));
            item.put("cachedTokens", row.get("cached"));
            item.put("cacheHitRate", rate(row.get("cached"), row.get("prompt")));
            item.put("avgSpeed", scale2(row.get("avgSpeed")));
            item.put("avgDuration", row.get("avgDuration"));
            item.put("errors", row.get("errors"));
            items.add(item);

            cnt += ((Number) row.get("count")).longValue();
            prompt += ((Number) row.get("prompt")).longValue();
            completion += ((Number) row.get("completion")).longValue();
            tok += ((Number) row.get("total")).longValue();
            cached += ((Number) row.get("cached")).longValue();
            errors += ((Number) row.get("errors")).longValue();
        }
        total.put("count", cnt);
        total.put("promptTokens", prompt);
        total.put("completionTokens", completion);
        total.put("totalTokens", tok);
        total.put("cachedTokens", cached);
        total.put("cacheHitRate", prompt > 0
                ? BigDecimal.valueOf(cached * 100.0 / prompt).setScale(2, RoundingMode.HALF_UP)
                : null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("total", total);
        return m;
    }

    private Object rate(Object cached, Object prompt) {
        long p = ((Number) prompt).longValue();
        if (p <= 0) {
            return null;
        }
        return BigDecimal.valueOf(((Number) cached).longValue() * 100.0 / p)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Object scale2(Object v) {
        if (v == null) {
            return null;
        }
        return new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP);
    }
}
