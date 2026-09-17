package com.llama.hub.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端更新 API Key 的入参载体。
 * 每个可改字段配一个 changed 标志：JSON 里没出现的字段不动，显式传 null 表示「不限 / 永久」。
 */
@Data
public class KeyUpdateCommand {

    private Long id;

    private boolean nameChanged;
    private String name;

    private boolean expiryChanged;
    /** null 表示永久有效 */
    private LocalDateTime expiresAt;

    private boolean activeChanged;
    private Boolean active;

    private boolean tokenQuotaChanged;
    /** null 表示不限 */
    private Long tokenQuota;

    private boolean requestQuotaChanged;
    /** null 表示不限 */
    private Long requestQuota;

    public boolean hasChanges() {
        return nameChanged || expiryChanged || activeChanged || tokenQuotaChanged || requestQuotaChanged;
    }
}