package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.DrugBaselineEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 麻精基数 Mapper（{@code m4_drug.drug_baseline}，V20）。
 *
 * <p>品种 + 柜唯一；更新使用乐观锁（{@code WHERE version = ?} + {@code version = version + 1}），
 * 数据库守卫保证品种/柜不可改、version 只增。
 */
@Mapper
public interface DrugBaselineMapper extends BaseMapper<DrugBaselineEntity> {

    @Insert("""
            INSERT INTO m4_drug.drug_baseline
                (id, drug_id, cabinet_code, department_id, quantity, created_at, created_by,
                 updated_at, updated_by, version)
            VALUES
                (#{id}, #{drugId}, #{cabinetCode}, #{departmentId}, #{quantity}, #{createdAt},
                 #{createdBy}, #{updatedAt}, #{updatedBy}, #{version})
            """)
    int insertBaseline(DrugBaselineEntity baseline);

    @Update("""
            UPDATE m4_drug.drug_baseline
               SET quantity = #{quantity},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int updateBaseline(DrugBaselineEntity baseline);

    @Select("""
            SELECT * FROM m4_drug.drug_baseline
             WHERE drug_id = #{drugId}::uuid AND cabinet_code = #{cabinetCode}
            """)
    DrugBaselineEntity selectByDrugAndCabinet(@Param("drugId") String drugId,
            @Param("cabinetCode") String cabinetCode);

    @Select("SELECT * FROM m4_drug.drug_baseline WHERE id = #{id}")
    DrugBaselineEntity selectByBaselineId(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT b.* FROM m4_drug.drug_baseline b
            JOIN m4_drug.drug d ON d.id = b.drug_id
            <where>
              <if test="cabinetCode != null and cabinetCode != ''">
                AND b.cabinet_code = #{cabinetCode}
              </if>
              <if test="departmentId != null and departmentId != ''">
                AND b.department_id = #{departmentId}::uuid
              </if>
              <if test="departmentIds != null and departmentIds.size() &gt; 0">
                AND b.department_id IN
                <foreach collection="departmentIds" item="deptId" open="(" separator=","
                         close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY b.cabinet_code, d.name
            </script>
            """)
    List<DrugBaselineEntity> selectBaselines(@Param("cabinetCode") String cabinetCode,
            @Param("departmentId") String departmentId,
            @Param("departmentIds") List<String> departmentIds);
}
