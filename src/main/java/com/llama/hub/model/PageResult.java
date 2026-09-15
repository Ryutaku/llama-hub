package com.llama.hub.model;

import lombok.Data;

import java.util.List;

/** 分页列表通用返回（调用日志 / 审计日志） */
@Data
public class PageResult<T> {
    private List<T> content;
    private long total;
    private int page;
    private int size;

    public PageResult() {
    }

    public PageResult(List<T> content, long total, int page, int size) {
        this.content = content;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
