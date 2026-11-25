package com.zhh.nl2sql.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 元数据同步执行结果 DTO
 */
@Data
@AllArgsConstructor
public class MetadataSyncResult {

    /** 本次同步成功写入向量库的表数量 */
    private int syncedTables;

    /** 本次同步成功写入向量库的指标数量 */
    private int syncedMetrics;

    /** 本次跳过的条目数（未启用 或 内容未变更） */
    private int skippedCount;

    /** 是否因为开关关闭或并发锁而整体跳过 */
    private boolean skipped;

    public static MetadataSyncResult skipped() {
        return new MetadataSyncResult(0, 0, 0, true);
    }

    @Override
    public String toString() {
        if (skipped) return "同步已跳过（开关关闭或任务并发）";
        return String.format("同步表=%d, 指标=%d, 跳过=%d", syncedTables, syncedMetrics, skippedCount);
    }
}