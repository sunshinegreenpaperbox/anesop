package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.CabinetHandoverEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 专柜交接 Mapper（{@code m4_drug.cabinet_handover}，V20）。
 *
 * <p>append-only：只提供 INSERT 与查询；{@code items} 为 jsonb 数组文本，写入时显式 cast。
 */
@Mapper
public interface CabinetHandoverMapper extends BaseMapper<CabinetHandoverEntity> {

    @Insert("""
            INSERT INTO m4_drug.cabinet_handover
                (id, cabinet_code, department_id, shift, handed_over_at, handover_from_id,
                 handover_from_name, handover_to_id, handover_to_name, items, first_signer_id,
                 first_signer_name, second_signer_id, second_signer_name, created_at, created_by)
            VALUES
                (#{id}, #{cabinetCode}, #{departmentId}, #{shift}, #{handedOverAt},
                 #{handoverFromId}, #{handoverFromName}, #{handoverToId}, #{handoverToName},
                 #{items}::jsonb, #{firstSignerId}, #{firstSignerName}, #{secondSignerId},
                 #{secondSignerName}, #{createdAt}, #{createdBy})
            """)
    int insertHandover(CabinetHandoverEntity handover);

    @Select("""
            <script>
            SELECT * FROM m4_drug.cabinet_handover
            <where>
              <if test="cabinetCode != null and cabinetCode != ''">
                AND cabinet_code = #{cabinetCode}
              </if>
              <if test="departmentId != null and departmentId != ''">
                AND department_id = #{departmentId}::uuid
              </if>
              <if test="departmentIds != null and departmentIds.size() &gt; 0">
                AND department_id IN
                <foreach collection="departmentIds" item="deptId" open="(" separator=","
                         close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY handed_over_at DESC, created_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<CabinetHandoverEntity> selectHandovers(@Param("cabinetCode") String cabinetCode,
            @Param("departmentId") String departmentId,
            @Param("departmentIds") List<String> departmentIds, @Param("limit") int limit);
}
