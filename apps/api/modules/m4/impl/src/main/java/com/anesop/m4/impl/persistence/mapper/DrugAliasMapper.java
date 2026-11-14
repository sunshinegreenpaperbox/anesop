package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.DrugAliasEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 药品别名 Mapper（{@code m4_drug.drug_alias}，V20）。
 *
 * <p>别名全局唯一（lower(alias)）；更新药品时整组替换（DELETE + INSERT，同一事务）。
 * 别名冲突同时检查其他药品的名称/通用名，保证“按名称解析麻精属性”无歧义。
 */
@Mapper
public interface DrugAliasMapper extends BaseMapper<DrugAliasEntity> {

    @Insert("""
            INSERT INTO m4_drug.drug_alias (id, drug_id, alias, created_at, created_by)
            VALUES (#{id}, #{drugId}, #{alias}, #{createdAt}, #{createdBy})
            """)
    int insertAlias(DrugAliasEntity alias);

    @Delete("DELETE FROM m4_drug.drug_alias WHERE drug_id = #{drugId}")
    int deleteByDrug(@Param("drugId") UUID drugId);

    @Select("""
            SELECT alias FROM m4_drug.drug_alias
             WHERE drug_id = #{drugId}
             ORDER BY created_at, alias
            """)
    List<String> selectAliases(@Param("drugId") UUID drugId);

    /**
     * 别名冲突检测：与其他药品的别名（lower）或名称/通用名（lower）冲突即返回 > 0。
     * {@code excludeDrugId} 传哨兵 UUID（全 0）表示新增（检查全部药品），避免 NULL 参数类型歧义。
     */
    @Select("""
            SELECT count(*) FROM (
                SELECT 1 FROM m4_drug.drug_alias a
                 WHERE lower(btrim(a.alias)) = lower(btrim(#{alias}))
                   AND a.drug_id <> #{excludeDrugId}::uuid
                UNION ALL
                SELECT 1 FROM m4_drug.drug d
                 WHERE (lower(btrim(d.name)) = lower(btrim(#{alias}))
                        OR lower(btrim(coalesce(d.generic_name, ''))) = lower(btrim(#{alias})))
                   AND d.id <> #{excludeDrugId}::uuid
            ) conflicts
            """)
    long countAliasConflict(@Param("alias") String alias,
            @Param("excludeDrugId") String excludeDrugId);
}
