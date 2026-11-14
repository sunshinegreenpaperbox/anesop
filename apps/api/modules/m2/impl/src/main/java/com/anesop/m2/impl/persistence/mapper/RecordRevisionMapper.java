package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.RecordRevisionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 修订版本 Mapper（{@code m2_anes.record_revision}，V17，M3-M2-03）。
 *
 * <p>append-only：只提供 INSERT 与查询（数据库触发器拒绝 UPDATE/DELETE/TRUNCATE）。
 * {@code snapshot} 以 jsonb 存储、以 JSON 文本读取；{@code (record_id, revision_no)} 唯一，
 * 服务层在记录单行锁内分配版本号，唯一约束仅作最后兜底。
 */
@Mapper
public interface RecordRevisionMapper extends BaseMapper<RecordRevisionEntity> {

    @Insert("""
            INSERT INTO m2_anes.record_revision
                (id, record_id, revision_no, reason_code, reason, changes, snapshot,
                 previous_revision_no, previous_signature_hash, signature_ref, signature_hash,
                 ca_status, created_at, created_by)
            VALUES
                (#{id}, #{recordId}, #{revisionNo}, #{reasonCode}, #{reason}, #{changes},
                 #{snapshot}::jsonb, #{previousRevisionNo}, #{previousSignatureHash},
                 #{signatureRef}, #{signatureHash}, #{caStatus}, #{createdAt}, #{createdBy})
            """)
    int insertRevision(RecordRevisionEntity revision);

    /** 修订版本列表（revisionNo 升序）。 */
    @Select("""
            SELECT id, record_id, revision_no, reason_code, reason, changes,
                   snapshot::text AS snapshot, previous_revision_no, previous_signature_hash,
                   signature_ref, signature_hash, ca_status, created_at, created_by
              FROM m2_anes.record_revision
             WHERE record_id = #{recordId}
             ORDER BY revision_no ASC
            """)
    List<RecordRevisionEntity> selectByRecord(@Param("recordId") UUID recordId);

    /** 指定版本（不存在返回 null）。 */
    @Select("""
            SELECT id, record_id, revision_no, reason_code, reason, changes,
                   snapshot::text AS snapshot, previous_revision_no, previous_signature_hash,
                   signature_ref, signature_hash, ca_status, created_at, created_by
              FROM m2_anes.record_revision
             WHERE record_id = #{recordId}
               AND revision_no = #{revisionNo}
            """)
    RecordRevisionEntity selectByRecordAndNo(@Param("recordId") UUID recordId,
            @Param("revisionNo") Integer revisionNo);
}
