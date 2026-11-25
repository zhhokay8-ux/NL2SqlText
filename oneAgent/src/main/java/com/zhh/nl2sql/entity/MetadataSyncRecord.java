package com.zhh.nl2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 元数据同步记录——记录每个向量文档最后一次同步的 MD5。
 * 内容未变则跳过重新 Embedding，节省 API 调用费用。
 */
@Data
@NoArgsConstructor
@TableName("metadata_sync_record")
public class MetadataSyncRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 向量文档唯一标识，格式：table:{datasourceId}:{tableName} 或 metric:{name} */
    private String docId;

    /** 文档内容的 MD5，内容不变则不重新 Embedding */
    private String contentHash;

    /** 最近一次同步时间 */
    private LocalDateTime syncedAt;

    /** 累计同步次数 */
    private Integer syncCount = 1;

    private LocalDateTime createdAt = LocalDateTime.now();

    public MetadataSyncRecord(String docId) {
        this.docId = docId;
        this.createdAt = LocalDateTime.now();
    }
}