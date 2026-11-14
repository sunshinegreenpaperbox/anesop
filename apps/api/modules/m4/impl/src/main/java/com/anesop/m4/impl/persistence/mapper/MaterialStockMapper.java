package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.MaterialStockEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次库存 Mapper（{@code m4_material.material_stock}，V23）。
 *
 * <p>入库使用 {@code INSERT ... ON CONFLICT DO UPDATE} 原子累加；唯一键冲突时只有效期一致才
 * 允许累加（不一致返回 0，服务层 409）。出库使用带 {@code quantity >= ?} 条件的原子扣减
 * （返回 0 表示库存不足或批次不存在，服务层 409），避免“先查后写”的并发超扣。
 */
@Mapper
public interface MaterialStockMapper extends BaseMapper<MaterialStockEntity> {

    /**
     * 入库/正调整：按 材料 + 批次 + 科室 原子累加；效期不一致时不更新（返回 0）。
     */
    @Insert("""
            INSERT INTO m4_material.material_stock
                (id, material_id, material_code, material_name, batch_no, department_id,
                 quantity, unit, expiry_date, created_at, updated_at, version)
            VALUES
                (#{id}, #{materialId}, #{materialCode}, #{materialName}, #{batchNo},
                 #{departmentId}, #{quantity}, #{unit}, #{expiryDate}, #{createdAt},
                 #{updatedAt}, #{version})
            ON CONFLICT (material_id, batch_no, department_id) DO UPDATE
               SET quantity = m4_material.material_stock.quantity + EXCLUDED.quantity,
                   updated_at = EXCLUDED.updated_at,
                   version = m4_material.material_stock.version + 1
             WHERE m4_material.material_stock.expiry_date
                   IS NOT DISTINCT FROM EXCLUDED.expiry_date
            """)
    int upsertIncrement(MaterialStockEntity stock);

    /** 原子扣减：库存不足/批次不存在返回 0（服务层 409）。 */
    @Update("""
            UPDATE m4_material.material_stock
               SET quantity = quantity - #{quantity},
                   updated_at = #{updatedAt},
                   version = version + 1
             WHERE id = #{id} AND quantity >= #{quantity}
            """)
    int decrement(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
            @Param("updatedAt") Instant updatedAt);

    @Select("""
            <script>
            SELECT * FROM m4_material.material_stock
            <where>
              <if test="materialId != null and materialId != ''">
                AND material_id = #{materialId}::uuid
              </if>
              <if test="departmentId != null and departmentId != ''">
                AND department_id = #{departmentId}::uuid
              </if>
              <if test="allowedDeptIds != null">
                AND department_id IN
                <foreach collection="allowedDeptIds" item="deptId" open="(" separator=","
                         close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY material_name, batch_no
            LIMIT #{limit}
            </script>
            """)
    List<MaterialStockEntity> selectStock(@Param("materialId") String materialId,
            @Param("departmentId") String departmentId,
            @Param("allowedDeptIds") List<String> allowedDeptIds, @Param("limit") int limit);

    @Select("""
            SELECT * FROM m4_material.material_stock
             WHERE material_id = #{materialId} AND batch_no = #{batchNo}
               AND department_id = #{departmentId}
            """)
    MaterialStockEntity selectByBatch(@Param("materialId") UUID materialId,
            @Param("batchNo") String batchNo, @Param("departmentId") UUID departmentId);

    @Select("SELECT quantity FROM m4_material.material_stock WHERE id = #{id}")
    BigDecimal selectQuantity(@Param("id") UUID id);

    @Select("""
            SELECT count(*) FROM m4_material.material_stock
             WHERE material_id = #{materialId} AND quantity > 0
            """)
    long countPositiveByMaterial(@Param("materialId") UUID materialId);
}
