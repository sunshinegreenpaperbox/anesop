package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.StockMovementEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 出入库流水 Mapper（{@code m4_material.stock_movement}，V23，append-only）。
 *
 * <p>只提供 INSERT 与查询：数据库触发器拒绝 UPDATE/DELETE/TRUNCATE（55000）；
 * 更正走反向 ADJUST（服务层强制填写原因）。
 */
@Mapper
public interface StockMovementMapper extends BaseMapper<StockMovementEntity> {

    @Insert("""
            INSERT INTO m4_material.stock_movement
                (id, movement_type, material_id, material_code, material_name, batch_no,
                 expiry_date, udi_code, quantity, unit, department_id, counterparty, reason,
                 balance_after, record_id, schedule_id, operator_id, operator_name,
                 occurred_at, created_at, created_by)
            VALUES
                (#{id}, #{movementType}, #{materialId}, #{materialCode}, #{materialName},
                 #{batchNo}, #{expiryDate}, #{udiCode}, #{quantity}, #{unit}, #{departmentId},
                 #{counterparty}, #{reason}, #{balanceAfter}, #{recordId}, #{scheduleId},
                 #{operatorId}, #{operatorName}, #{occurredAt}, #{createdAt}, #{createdBy})
            """)
    int insertMovement(StockMovementEntity movement);

    @Select("""
            <script>
            SELECT * FROM m4_material.stock_movement
            <where>
              <if test="materialId != null and materialId != ''">
                AND material_id = #{materialId}::uuid
              </if>
              <if test="departmentId != null and departmentId != ''">
                AND department_id = #{departmentId}::uuid
              </if>
              <if test="from != null">AND occurred_at &gt;= #{from}</if>
              <if test="to != null">AND occurred_at &lt;= #{to}</if>
              <if test="allowedDeptIds != null">
                AND department_id IN
                <foreach collection="allowedDeptIds" item="deptId" open="(" separator=","
                         close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY occurred_at DESC, id
            LIMIT #{limit}
            </script>
            """)
    List<StockMovementEntity> selectMovements(@Param("materialId") String materialId,
            @Param("departmentId") String departmentId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("limit") int limit);

    @Select("""
            SELECT * FROM m4_material.stock_movement
             WHERE udi_code = #{udiCode} AND department_id = #{departmentId}
             ORDER BY occurred_at DESC, id
            """)
    List<StockMovementEntity> selectByUdi(@Param("udiCode") String udiCode,
            @Param("departmentId") UUID departmentId);
}
