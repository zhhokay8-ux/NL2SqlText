package com.zhh.nl2sql.scheduler;

import com.zhh.nl2sql.dto.MetadataSyncResult;
import com.zhh.nl2sql.service.MetadataSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 元数据定时同步调度器。
 * <p>
 * 两个定时任务：
 * - 全量同步：每天凌晨 2 点，cron 从配置文件读取
 * - 增量检查：每 30 分钟，依赖 MD5 Diff 自动跳过未变更内容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataSyncScheduler {

    private final MetadataSyncService syncService;

    /**
     * 全量同步——每天凌晨 2 点触发。
     * cron 表达式从 datasource-meta.yml 读取，不硬编码。
     */
    @Scheduled(cron = "${metadata.sync.cron:0 0 2 * * ?}")
    public void fullSync() {
        log.info("[元数据] 定时全量同步开始");
        long start = System.currentTimeMillis();
        MetadataSyncResult result = syncService.syncAll();
        log.info("[元数据] 定时全量同步完成，耗时 {}ms，{}", System.currentTimeMillis() - start, result);
    }

    /**
     * 增量检查——每 30 分钟触发一次。
     * syncAll 内部有 MD5 Diff，只有内容真正变更时才重新 Embedding。
     */
    @Scheduled(cron = "${metadata.sync.incremental-cron:0 */30 * * * ?}")
    public void incrementalSync() {
        log.debug("[元数据] 增量同步检查触发");
        syncService.syncAll();
    }

    /**
     * 手动触发入口，供 MetadataSyncController 调用。
     */
    public MetadataSyncResult triggerManualSync() {
        log.info("[元数据] 手动触发同步");
        return syncService.syncAll();
    }
}