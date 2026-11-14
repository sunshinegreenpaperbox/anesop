package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.DrugEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 药品主数据 Mapper（{@code m4_drug.drug}，V20）。
 *
 * <p>唯一性以数据库唯一索引兜底（编码 lower 唯一、名称唯一），服务层先查重返回 409；
 * 更新使用乐观锁（{@code WHERE version = ?} + {@code version = version + 1}）。
 * 名称解析（T-4 麻精判定）按“名称 → 通用名 → 别名”优先级命中一条。
 */
@Mapper
public interface DrugMapper extends BaseMapper<DrugEntity> {

    @Insert("""
            INSERT INTO m4_drug.drug
                (id, code, name, generic_name, specification, unit, dosage_form, category,
                 narcotic, high_risk, status, remark, created_at, created_by, updated_at,
                 updated_by, version)
            VALUES
                (#{id}, #{code}, #{name}, #{genericName}, #{specification}, #{unit},
                 #{dosageForm}, #{category}, #{narcotic}, #{highRisk}, #{status}, #{remark},
                 #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{version})
            """)
    int insertDrug(DrugEntity drug);

    /** 乐观锁更新：编码不可改；版本过期时影响行数为 0（服务层 409）。 */
    @Update("""
            UPDATE m4_drug.drug
               SET name = #{name},
                   generic_name = #{genericName},
                   specification = #{specification},
                   unit = #{unit},
                   dosage_form = #{dosageForm},
                   category = #{category},
                   narcotic = #{narcotic},
                   high_risk = #{highRisk},
                   status = #{status},
                   remark = #{remark},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int updateDrug(DrugEntity drug);

    @Select("SELECT * FROM m4_drug.drug WHERE id = #{id}")
    DrugEntity selectByDrugId(@Param("id") UUID id);

    @Select("SELECT * FROM m4_drug.drug WHERE lower(btrim(code)) = lower(btrim(#{code})) LIMIT 1")
    DrugEntity selectByCode(@Param("code") String code);

    /**
     * T-4 名称解析候选：名称/通用名/别名（trim + 忽略大小写）命中的不同药品。
     *
     * <p>最多取 3 条；调用方必须按“0 条未收录、1 条唯一、>1 条歧义”处理，禁止静默取第一条。
     * 数据库唯一索引（名称/通用名/别名归一化唯一）是第一道防线，本查询是 fail-closed 兜底。
     */
    @Select("""
            SELECT DISTINCT d.* FROM m4_drug.drug d
             WHERE lower(btrim(d.name)) = lower(btrim(#{name}))
                OR lower(btrim(coalesce(d.generic_name, ''))) = lower(btrim(#{name}))
                OR EXISTS (
                       SELECT 1 FROM m4_drug.drug_alias a
                        WHERE a.drug_id = d.id
                          AND lower(btrim(a.alias)) = lower(btrim(#{name}))
                   )
             ORDER BY d.name
             LIMIT 3
            """)
    List<DrugEntity> selectCandidatesForDictionaryByName(@Param("name") String name);

    @Select("""
            <script>
            SELECT * FROM m4_drug.drug
            <where>
              <if test="keyword != null and keyword != ''">
                (lower(name) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(code) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(coalesce(generic_name, '')) LIKE lower(concat('%', #{keyword}, '%'))
                 OR EXISTS (
                        SELECT 1 FROM m4_drug.drug_alias a
                         WHERE a.drug_id = m4_drug.drug.id
                           AND lower(a.alias) LIKE lower(concat('%', #{keyword}, '%'))
                    ))
              </if>
              <if test="status != null and status != ''">
                AND status = #{status}
              </if>
            </where>
            ORDER BY name
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DrugEntity> selectPage(@Param("keyword") String keyword, @Param("status") String status,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT count(*) FROM m4_drug.drug
            <where>
              <if test="keyword != null and keyword != ''">
                (lower(name) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(code) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(coalesce(generic_name, '')) LIKE lower(concat('%', #{keyword}, '%'))
                 OR EXISTS (
                        SELECT 1 FROM m4_drug.drug_alias a
                         WHERE a.drug_id = m4_drug.drug.id
                           AND lower(a.alias) LIKE lower(concat('%', #{keyword}, '%'))
                    ))
              </if>
              <if test="status != null and status != ''">
                AND status = #{status}
              </if>
            </where>
            </script>
            """)
    long countPage(@Param("keyword") String keyword, @Param("status") String status);

    /** 用药录入联想：只返回启用药品，按“名称前缀优先、名称”排序。 */
    @Select("""
            SELECT d.* FROM m4_drug.drug d
             WHERE d.status = 'ACTIVE'
               AND (lower(d.name) LIKE lower(concat(#{q}, '%'))
                    OR lower(d.name) LIKE lower(concat('%', #{q}, '%'))
                    OR lower(coalesce(d.generic_name, '')) LIKE lower(concat('%', #{q}, '%'))
                    OR EXISTS (
                           SELECT 1 FROM m4_drug.drug_alias a
                            WHERE a.drug_id = d.id AND lower(a.alias) LIKE lower(concat('%', #{q}, '%'))
                       ))
             ORDER BY CASE WHEN lower(d.name) LIKE lower(concat(#{q}, '%')) THEN 0 ELSE 1 END,
                      d.name
             LIMIT #{limit}
            """)
    List<DrugEntity> searchActive(@Param("q") String q, @Param("limit") int limit);

    @Select("""
            SELECT count(*) FROM m4_drug.drug d
             WHERE lower(d.code) = lower(#{code}) AND d.id <> #{excludeId}::uuid
            """)
    long countCodeConflict(@Param("code") String code, @Param("excludeId") String excludeId);

    /**
     * 名称/通用名冲突检测（trim + 忽略大小写）：与其他药品的名称、通用名或别名任一相同即冲突。
     *
     * <p>同一药品自身的名称/通用名/别名不视为冲突（{@code excludeId} 排除）；
     * 别名撞他人名称/通用名与名称撞他人别名都会让名称解析出现歧义，必须 409。
     */
    @Select("""
            SELECT count(*) FROM (
                SELECT 1 FROM m4_drug.drug d
                 WHERE (lower(btrim(d.name)) = lower(btrim(#{name}))
                        OR lower(btrim(coalesce(d.generic_name, ''))) = lower(btrim(#{name})))
                   AND d.id <> #{excludeId}::uuid
                UNION ALL
                SELECT 1 FROM m4_drug.drug_alias a
                 WHERE lower(btrim(a.alias)) = lower(btrim(#{name}))
                   AND a.drug_id <> #{excludeId}::uuid
            ) conflicts
            """)
    long countNameConflict(@Param("name") String name, @Param("excludeId") String excludeId);
}
