package com.zhh.nl2sql.service;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhh.nl2sql.config.MetaProperties;
import com.zhh.nl2sql.dto.MetadataSyncResult;
import com.zhh.nl2sql.entity.MetadataSyncRecord;
import com.zhh.nl2sql.mapper.MetadataSyncRecordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 元数据同步核心服务。
 * <p>
 * 职责：
 * 1. 启动时根据 datasource-meta.yml 动态初始化各数仓的 Druid 连接池
 * 2. 从数仓 JDBC DatabaseMetaData 拉取真实字段信息
 * 3. 合并配置的业务含义（枚举值、口径约束、敏感字段标记）
 * 4. MD5 增量 Diff：内容未变则跳过 Embedding，节省费用
 * 5. 写入 pgvector VectorStore
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataSyncService {

    private final MetaProperties props;
    private final VectorStore vectorStore;
    private final MetadataSyncRecordMapper syncRecordMapper;

    /** 按 datasource-id 索引的数仓连接池，启动时初始化 */
    private final Map<String, DataSource> dwDataSources = new ConcurrentHashMap<>();

    /** 防止并发重复同步 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ── 初始化 ────────────────────────────────────────────────

    @PostConstruct
    public void initDataSources() {
        for (MetaProperties.DataSourceConfig cfg : props.getDatasources()) {
            try {
                DruidDataSource ds = new DruidDataSource();
                ds.setUrl(cfg.getUrl());
                ds.setUsername(cfg.getUsername());
                ds.setPassword(cfg.getPassword());
                ds.setDriverClassName(cfg.getDriver());
                ds.setInitialSize(1);
                ds.setMinIdle(1);
                ds.setMaxActive(3);          // 数仓连接要保守，避免占满
                ds.setMaxWait(10_000);
                ds.setTestWhileIdle(true);
                ds.setValidationQuery("SELECT 1");
                ds.init();
                dwDataSources.put(cfg.getId(), ds);
                log.info("[元数据] 数仓数据源初始化完成: id={}, name={}", cfg.getId(), cfg.getName());
            } catch (Exception e) {
                log.error("[元数据] 数仓数据源初始化失败: id={}, error={}", cfg.getId(), e.getMessage());
            }
        }
    }

    // ── 同步入口 ──────────────────────────────────────────────

    /**
     * 全量同步所有启用的表和指标到向量知识库。
     * 内部通过 MD5 Diff 自动跳过未变更的文档。
     */
    public MetadataSyncResult syncAll() {
        if (!props.getSync().isEnabled()) {
            log.info("[元数据] 同步开关已关闭，跳过");
            return MetadataSyncResult.skipped();
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("[元数据] 上一次同步尚未完成，本次跳过");
            return MetadataSyncResult.skipped();
        }

        int syncedTables = 0, syncedMetrics = 0, skippedCount = 0;
        try {
            // 同步表/字段元数据
            for (MetaProperties.TableConfig tableConfig : props.getTables()) {
                if (!tableConfig.isEnabled()) {
                    skippedCount++;
                    continue;
                }
                try {
                    if (syncTableMetadata(tableConfig)) {
                        syncedTables++;
                    } else {
                        skippedCount++;
                    }
                } catch (Exception e) {
                    log.error("[元数据] 表 {} 同步失败: {}", tableConfig.getTableName(), e.getMessage());
                }
            }

            // 同步指标定义
            for (MetaProperties.MetricConfig metricConfig : props.getMetrics()) {
                try {
                    if (syncMetricDefinition(metricConfig)) {
                        syncedMetrics++;
                    } else {
                        skippedCount++;
                    }
                } catch (Exception e) {
                    log.error("[元数据] 指标 {} 同步失败: {}", metricConfig.getName(), e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }

        MetadataSyncResult result = new MetadataSyncResult(syncedTables, syncedMetrics, skippedCount, false);
        log.info("[元数据] 同步完成: {}", result);
        return result;
    }

    // ── 表元数据同步 ──────────────────────────────────────────

    private boolean syncTableMetadata(MetaProperties.TableConfig cfg) throws Exception {
        DataSource dwDs = dwDataSources.get(cfg.getDatasourceId());
        if (dwDs == null) {
            log.warn("[元数据] 数据源 {} 不存在，跳过表 {}", cfg.getDatasourceId(), cfg.getTableName());
            return false;
        }

        List<ColumnInfo> ddlColumns = fetchColumnsFromDw(dwDs, cfg.getTableName());
        String content = buildTableDocument(cfg, ddlColumns);
        String docId = "table:" + cfg.getDatasourceId() + ":" + cfg.getTableName();

        return saveIfChanged(docId, content, Map.of(
            "type", "table_schema",
            "table_name", cfg.getTableName(),
            "display_name", cfg.getDisplayName() != null ? cfg.getDisplayName() : cfg.getTableName(),
            "datasource_id", cfg.getDatasourceId(),
            "tags", String.join(",", cfg.getTags())
        ));
    }

    /**
     * 通过 JDBC DatabaseMetaData 拉取表的真实字段列表。
     * 兼容 MySQL 和 PostgreSQL（REMARKS 字段在 PG 需要开启 remarksReporting）。
     */
    private List<ColumnInfo> fetchColumnsFromDw(DataSource ds, String tableName) throws Exception {
        List<ColumnInfo> columns = new ArrayList<>();
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.setName(rs.getString("COLUMN_NAME").toLowerCase());
                    col.setType(rs.getString("TYPE_NAME"));
                    col.setComment(rs.getString("REMARKS"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    /**
     * 组装表向量文档。
     * 文档质量直接决定 RAG 召回准确率——字段注释越丰富，召回越精准。
     */
    private String buildTableDocument(MetaProperties.TableConfig cfg, List<ColumnInfo> ddlColumns) {
        // 把配置的字段补充信息转为 Map，方便按字段名查找
        Map<String, MetaProperties.ColumnConfig> cfgColMap = cfg.getColumns().stream()
            .collect(Collectors.toMap(c -> c.getName().toLowerCase(), c -> c, (a, b) -> a));

        StringBuilder sb = new StringBuilder();
        sb.append("表名: ").append(cfg.getTableName()).append("\n");
        sb.append("业务名称: ").append(cfg.getDisplayName()).append("\n");
        sb.append("业务含义: ").append(cfg.getDescription()).append("\n");
        sb.append("业务标签: ").append(String.join(", ", cfg.getTags())).append("\n\n");
        sb.append("字段列表:\n");

        for (ColumnInfo col : ddlColumns) {
            MetaProperties.ColumnConfig cfgCol = cfgColMap.get(col.getName());

            // 优先用配置的业务名称，兜底用 DDL REMARKS，最后用字段名
            String displayName = (cfgCol != null && StringUtils.hasText(cfgCol.getBusinessName()))
                ? cfgCol.getBusinessName()
                : (StringUtils.hasText(col.getComment()) ? col.getComment() : col.getName());

            sb.append("  - ").append(col.getName())
              .append(" (").append(col.getType()).append(")")
              .append(": ").append(displayName).append("\n");

            // 枚举值映射：解决 status=1 是什么意思的问题
            if (cfgCol != null && !cfgCol.getEnumValues().isEmpty()) {
                sb.append("    枚举值: ");
                cfgCol.getEnumValues().forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
                sb.append("\n");
            }

            // 查询必须附加的条件（告知 LLM 生成 SQL 时必须加这个 WHERE）
            if (cfgCol != null && StringUtils.hasText(cfgCol.getQueryConstraint())) {
                sb.append("    查询必须附加条件: ").append(cfgCol.getQueryConstraint()).append("\n");
            }

            // 敏感字段：AST 白名单校验会拦截，同时在文档中标注让 LLM 知道
            if (cfgCol != null && cfgCol.isSensitive()) {
                sb.append("    【敏感字段，禁止在 SQL 中直接查询】\n");
            }
        }
        return sb.toString();
    }

    // ── 指标定义同步 ──────────────────────────────────────────

    private boolean syncMetricDefinition(MetaProperties.MetricConfig cfg) {
        String docId = "metric:" + cfg.getName();

        StringBuilder sb = new StringBuilder();
        sb.append("指标名称: ").append(cfg.getName()).append("\n");
        if (!cfg.getAliases().isEmpty()) {
            sb.append("同义词: ").append(String.join(", ", cfg.getAliases())).append("\n");
        }
        sb.append("业务含义: ").append(cfg.getDescription()).append("\n");
        sb.append("计算公式: ").append(cfg.getFormula()).append("\n");
        sb.append("关联表: ").append(String.join(", ", cfg.getRelatedTables())).append("\n");
        if (!cfg.getConstraints().isEmpty()) {
            sb.append("必须满足的查询条件:\n");
            cfg.getConstraints().forEach(c -> sb.append("  - ").append(c).append("\n"));
        }
        if (StringUtils.hasText(cfg.getExampleSql())) {
            sb.append("参考SQL:\n").append(cfg.getExampleSql()).append("\n");
        }

        return saveIfChanged(docId, sb.toString(), Map.of(
            "type", "metric_definition",
            "metric_name", cfg.getName(),
            "aliases", String.join(",", cfg.getAliases()),
            "related_tables", String.join(",", cfg.getRelatedTables())
        ));
    }

    // ── 增量 Diff + 写向量库 ──────────────────────────────────

    /**
     * MD5 Diff：内容未变则跳过，变更则删旧插新。
     *
     * @return true=发生了实际写入，false=内容未变跳过
     */
    private boolean saveIfChanged(String docId, String content, Map<String, Object> metadata) {
        String hash = DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8));

        MetadataSyncRecord existing = syncRecordMapper.selectOne(
            new LambdaQueryWrapper<MetadataSyncRecord>().eq(MetadataSyncRecord::getDocId, docId)
        );

        if (existing != null && hash.equals(existing.getContentHash())) {
            log.debug("[元数据] {} 内容未变更，跳过 Embedding", docId);
            return false;
        }

        // 构建向量文档并写入
        Document doc = new Document(content, metadata);
        try {
            // 先删旧向量（Spring AI VectorStore 按 id 删除）
            if (existing != null) {
                vectorStore.delete(List.of(docId));
            }
        } catch (Exception e) {
            log.warn("[元数据] 删除旧向量失败，继续写入新向量: {}", e.getMessage());
        }
        vectorStore.add(List.of(doc));

        // 更新或新建同步记录
        if (existing != null) {
            existing.setContentHash(hash);
            existing.setSyncedAt(LocalDateTime.now());
            existing.setSyncCount(existing.getSyncCount() + 1);
            syncRecordMapper.updateById(existing);
        } else {
            MetadataSyncRecord record = new MetadataSyncRecord(docId);
            record.setContentHash(hash);
            record.setSyncedAt(LocalDateTime.now());
            syncRecordMapper.insert(record);
        }

        log.info("[元数据] {} 同步成功（Embedding 已更新）", docId);
        return true;
    }

    // ── 内部 DTO ──────────────────────────────────────────────

    @lombok.Data
    private static class ColumnInfo {
        private String name;
        private String type;
        private String comment;
    }
}