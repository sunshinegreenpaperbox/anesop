package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.NarcoticRegisterEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 麻精专册 Mapper（{@code m4_drug.narcotic_register}，V20）。
 *
 * <p>append-only：只提供 INSERT 与查询，无 UPDATE/DELETE 方法；数据库触发器为第二道防线。
 * 列表按科室数据范围过滤（{@code departmentIds} 为空表示不限制，仅在全院范围时由服务层传入）。
 */
@Mapper
public interface NarcoticRegisterMapper extends BaseMapper<NarcoticRegisterEntity> {

    @Insert("""
            INSERT INTO m4_drug.narcotic_register
                (id, drug_id, drug_name, batch_no, quantity, unit, prescription_no, used_by,
                 department_id, usage_at, destination, first_signer_id, first_signer_name,
                 second_signer_id, second_signer_name, created_at, created_by)
            VALUES
                (#{id}, #{drugId}, #{drugName}, #{batchNo}, #{quantity}, #{unit},
                 #{prescriptionNo}, #{usedBy}, #{departmentId}, #{usageAt}, #{destination},
                 #{firstSignerId}, #{firstSignerName}, #{secondSignerId},
                 #{secondSignerName}, #{createdAt}, #{createdBy})
            """)
    int insertRegister(NarcoticRegisterEntity register);

    @Select("""
            <script>
            SELECT * FROM m4_drug.narcotic_register
            <where>
              <if test="departmentId != null and departmentId != ''">
                AND department_id = #{departmentId}::uuid
              </if>
              <if test="drugId != null and drugId != ''">
                AND drug_id = #{drugId}::uuid
              </if>
              <if test="from != null">AND usage_at &gt;= #{from}</if>
              <if test="to != null">AND usage_at &lt;= #{to}</if>
              <if test="departmentIds != null and departmentIds.size() &gt; 0">
                AND department_id IN
                <foreach collection="departmentIds" item="deptId" open="(" separator=","
                         close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY usage_at DESC, created_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<NarcoticRegisterEntity> selectRegisters(@Param("departmentId") String departmentId,
            @Param("drugId") String drugId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("departmentIds") List<String> departmentIds,
            @Param("limit") int limit);

    @Select("SELECT count(*) FROM m4_drug.narcotic_register")
    long countAll();
}
