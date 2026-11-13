package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.ConsentTemplateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 知情同意模板 Mapper（{@code m2_anes.consent_template}）。
 *
 * <p>内容只读：模板由迁移或后续「模板管理」任务写入，本期不提供创建/内容更新接口；
 * 数据库触发器保证内容 append-only（内容 UPDATE/DELETE 均 55000）。唯一允许的写操作是
 * {@link #retire}（ACTIVE → RETIRED 一次），由 {@code POST .../consent-templates/{id}/retire}
 * 调用，条件更新返回 0 行表示已停用或并发修改（服务层映射 409）。
 */
@Mapper
public interface ConsentTemplateMapper extends BaseMapper<ConsentTemplateEntity> {

    /** ACTIVE → RETIRED；返回 0 表示不存在或已停用。 */
    @Update("""
            UPDATE m2_anes.consent_template
               SET status = 'RETIRED',
                   retired_at = #{retiredAt},
                   retired_by = #{retiredBy},
                   updated_at = #{retiredAt},
                   updated_by = #{retiredBy}
             WHERE id = #{id}
               AND status = 'ACTIVE'
            """)
    int retire(@Param("id") UUID id,
            @Param("retiredAt") Instant retiredAt,
            @Param("retiredBy") UUID retiredBy);
}
