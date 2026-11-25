package com.zhh.nl2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhh.nl2sql.entity.MetadataSyncRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 元数据同步记录 Mapper
 * 继承 BaseMapper 获得 insert/update/selectById 等基础能力
 */
@Mapper
public interface MetadataSyncRecordMapper extends BaseMapper<MetadataSyncRecord> {

    /**
     * 按 docId 查询记录，用于增量 Diff 判断内容是否变更
     */
    @Select("SELECT * FROM metadata_sync_record WHERE doc_id = #{docId} LIMIT 1")
    Optional<MetadataSyncRecord> findByDocId(String docId);
}