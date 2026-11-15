package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.MaterialStockAlertRow;
import com.anesop.m4.impl.persistence.entity.MaterialStockEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * 库存预警聚合（V28）：材料 × 科室 粒度的可用量、批次数与最近效期。
     *
     * <p>只统计 {@code status=ACTIVE} 的耗材与调用者可见科室（{@code allowedDeptIds} 为 null
     * 表示 ALL 不过滤）；预警条件在 {@code HAVING} 中判定，保证 {@code LIMIT} 前先过滤：
     * <ul>
     *   <li>{@code LOW_STOCK}：已配置阈值且可用量严格低于阈值（等于阈值不预警）；</li>
     *   <li>{@code ZERO_STOCK}：可用量合计 = 0；</li>
     *   <li>{@code NEAR_EXPIRY}：存在数量 &gt; 0 且效期 ≤ {@code nearExpiryCutoff} 的批次
     *       （含已过期，由服务层按 {@code daysToExpiry} 区分）。</li>
     * </ul>
     * {@code alertType} 为空时命中任意一类。排序固定：零库存优先、材料名称、科室标识。
     */
    @Select("""
            <script>
            SELECT m.id AS material_id,
                   m.code AS material_code,
                   m.name AS material_name,
                   m.unit AS unit,
                   s.department_id AS department_id,
                   m.low_stock_threshold AS low_stock_threshold,
                   SUM(s.quantity) AS available_quantity,
                   COUNT(*) AS batch_count,
                   MIN(CASE WHEN s.quantity &gt; 0 THEN s.expiry_date END) AS nearest_expiry_date,
                   COUNT(*) FILTER (WHERE s.quantity &gt; 0 AND s.expiry_date IS NOT NULL
                                    AND s.expiry_date &lt;= #{nearExpiryCutoff})
                       AS near_expiry_batch_count
              FROM m4_material.material_stock s
              JOIN m4_material.material m ON m.id = s.material_id
             WHERE m.status = 'ACTIVE'
             <if test="departmentId != null and departmentId != ''">
               AND s.department_id = #{departmentId}::uuid
             </if>
             <if test="allowedDeptIds != null">
               AND s.department_id IN
               <foreach collection="allowedDeptIds" item="deptId" open="(" separator=","
                        close=")">
                 #{deptId}::uuid
               </foreach>
             </if>
             GROUP BY m.id, m.code, m.name, m.unit, m.low_stock_threshold, s.department_id
            HAVING
            <choose>
              <when test="alertType == 'LOW_STOCK'">
                m.low_stock_threshold IS NOT NULL
                AND SUM(s.quantity) &lt; m.low_stock_threshold
              </when>
              <when test="alertType == 'ZERO_STOCK'">
                SUM(s.quantity) = 0
              </when>
              <when test="alertType == 'NEAR_EXPIRY'">
                COUNT(*) FILTER (WHERE s.quantity &gt; 0 AND s.expiry_date IS NOT NULL
                                 AND s.expiry_date &lt;= #{nearExpiryCutoff}) &gt; 0
              </when>
              <otherwise>
                (m.low_stock_threshold IS NOT NULL
                 AND SUM(s.quantity) &lt; m.low_stock_threshold)
                OR SUM(s.quantity) = 0
                OR COUNT(*) FILTER (WHERE s.quantity &gt; 0 AND s.expiry_date IS NOT NULL
                                    AND s.expiry_date &lt;= #{nearExpiryCutoff}) &gt; 0
              </otherwise>
            </choose>
             ORDER BY CASE WHEN SUM(s.quantity) = 0 THEN 0 ELSE 1 END,
                      m.name, s.department_id
             LIMIT #{limit}
            </script>
            """)
    List<MaterialStockAlertRow> selectStockAlerts(@Param("departmentId") String departmentId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("nearExpiryCutoff") LocalDate nearExpiryCutoff,
            @Param("alertType") String alertType, @Param("limit") int limit);
}
