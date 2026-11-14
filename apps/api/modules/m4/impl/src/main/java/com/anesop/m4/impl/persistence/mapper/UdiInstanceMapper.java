package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.UdiInstanceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * UDI 实例 Mapper（{@code m4_material.udi_instance}，V23，一物一码）。
 *
 * <p>UDI-DI + UDI-PI 唯一（服务层先查重 409，唯一索引并发兜底）；使用/退回更新带
 * {@code status = 'IN_STOCK' AND version = ?} 条件（过期/已使用 409）；状态迁移由数据库
 * 触发器二次守卫（55000）。
 */
@Mapper
public interface UdiInstanceMapper extends BaseMapper<UdiInstanceEntity> {

    @Insert("""
            INSERT INTO m4_material.udi_instance
                (id, udi_code, udi_di, udi_pi, serial_number, material_id, material_code,
                 material_name, batch_no, expiry_date, department_id, status, record_id,
                 schedule_id, used_at, used_by, used_by_name, created_at, created_by,
                 updated_at, updated_by, version)
            VALUES
                (#{id}, #{udiCode}, #{udiDi}, #{udiPi}, #{serialNumber}, #{materialId},
                 #{materialCode}, #{materialName}, #{batchNo}, #{expiryDate}, #{departmentId},
                 #{status}, #{recordId}, #{scheduleId}, #{usedAt}, #{usedBy}, #{usedByName},
                 #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{version})
            """)
    int insertUdi(UdiInstanceEntity instance);

    @Select("SELECT * FROM m4_material.udi_instance WHERE udi_code = #{udiCode}")
    UdiInstanceEntity selectByUdiCode(@Param("udiCode") String udiCode);

    @Select("SELECT * FROM m4_material.udi_instance WHERE udi_code = #{udiCode} FOR UPDATE")
    UdiInstanceEntity selectByUdiCodeForUpdate(@Param("udiCode") String udiCode);

    @Select("""
            SELECT count(*) FROM m4_material.udi_instance
             WHERE lower(btrim(udi_di)) = lower(btrim(#{udiDi}))
               AND lower(btrim(udi_pi)) = lower(btrim(#{udiPi}))
            """)
    long countByUdiDiPi(@Param("udiDi") String udiDi, @Param("udiPi") String udiPi);

    /** 某耗材已登记的 UDI 实例数（无论状态）：用于阻止修改/清空 UDI-DI 破坏扫码反查。 */
    @Select("SELECT count(*) FROM m4_material.udi_instance WHERE material_id = #{materialId}")
    long countByMaterialId(@Param("materialId") UUID materialId);

    @Select("""
            <script>
            SELECT * FROM m4_material.udi_instance
             WHERE udi_code = #{udiCode}
             <if test="allowedDeptIds != null">
               AND department_id IN
               <foreach collection="allowedDeptIds" item="deptId" open="(" separator=","
                        close=")">
                 #{deptId}::uuid
               </foreach>
             </if>
            </script>
            """)
    UdiInstanceEntity selectScoped(@Param("udiCode") String udiCode,
            @Param("allowedDeptIds") List<String> allowedDeptIds);

    /** 使用：仅 IN_STOCK 且版本匹配可更新（返回 0 → 409）。 */
    @Update("""
            UPDATE m4_material.udi_instance
               SET status = 'USED',
                   record_id = #{recordId},
                   schedule_id = #{scheduleId},
                   used_at = #{usedAt},
                   used_by = #{usedBy},
                   used_by_name = #{usedByName},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND status = 'IN_STOCK' AND version = #{version}
            """)
    int markUsed(@Param("id") UUID id, @Param("version") int version,
            @Param("recordId") UUID recordId, @Param("scheduleId") UUID scheduleId,
            @Param("usedAt") Instant usedAt, @Param("usedBy") UUID usedBy,
            @Param("usedByName") String usedByName, @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") UUID updatedBy);

    /** 退回：仅 IN_STOCK 且版本匹配可更新（返回 0 → 409）。 */
    @Update("""
            UPDATE m4_material.udi_instance
               SET status = 'RETURNED',
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND status = 'IN_STOCK' AND version = #{version}
            """)
    int markReturned(@Param("id") UUID id, @Param("version") int version,
            @Param("updatedAt") Instant updatedAt, @Param("updatedBy") UUID updatedBy);
}
