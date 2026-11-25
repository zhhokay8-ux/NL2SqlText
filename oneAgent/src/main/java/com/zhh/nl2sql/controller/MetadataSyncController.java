package com.zhh.nl2sql.controller;

import com.zhh.nl2sql.dto.MetadataSyncResult;
import com.zhh.nl2sql.scheduler.MetadataSyncScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 元数据同步管理接口。
 * <p>
 * 提供手动触发同步的 HTTP 接口，方便在数仓表结构变更后立即刷新知识库，
 * 不需要等待定时任务。
 */
@RestController
@RequestMapping("/admin/metadata")
@RequiredArgsConstructor
public class MetadataSyncController {

    private final MetadataSyncScheduler scheduler;

    /**
     * 手动触发全量同步。
     * <p>
     * 使用场景：数仓新增表或字段后，立即调用此接口刷新向量知识库。
     * curl -X POST http://localhost:8080/admin/metadata/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<MetadataSyncResult> manualSync() {
        MetadataSyncResult result = scheduler.triggerManualSync();
        return ResponseEntity.ok(result);
    }
}