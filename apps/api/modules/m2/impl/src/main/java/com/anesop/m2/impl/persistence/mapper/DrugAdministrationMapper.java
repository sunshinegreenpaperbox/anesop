package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.DrugAdministrationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用药记录 Mapper（{@code m2_anes.drug_administration}，V13）。
 *
 * <p>append-only：只提供 INSERT 与查询；麻精双人核对字段由数据库 CHECK 兜底。
 */
@Mapper
public interface DrugAdministrationMapper extends BaseMapper<DrugAdministrationEntity> {

    @Insert("""
            INSERT INTO m2_anes.drug_administration
                (id, record_id, drug_name, dose, unit, route, administered_at, narcotic,
                 second_checker_id, second_checker_name, note, created_at, created_by)
            VALUES
                (#{id}, #{recordId}, #{drugName}, #{dose}, #{unit}, #{route}, #{administeredAt},
                 #{narcotic}, #{secondCheckerId}, #{secondCheckerName}, #{note}, #{createdAt},
                 #{createdBy})
            """)
    int insertDrug(DrugAdministrationEntity drug);

    /** 按给药时间倒序（同一时间按录入时间倒序）。 */
    @Select("""
            SELECT id, record_id, drug_name, dose, unit, route, administered_at, narcotic,
                   second_checker_id, second_checker_name, note, created_at, created_by
              FROM m2_anes.drug_administration
             WHERE record_id = #{recordId}
             ORDER BY administered_at DESC, created_at DESC
            """)
    List<DrugAdministrationEntity> selectByRecord(@Param("recordId") UUID recordId);
}
