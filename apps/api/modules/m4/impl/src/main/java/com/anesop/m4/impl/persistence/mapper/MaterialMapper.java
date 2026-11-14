package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.MaterialEntity;
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
 * 耗材主数据 Mapper（{@code m4_material.material}，V23）。
 *
 * <p>唯一性以数据库唯一索引兜底（编码/名称 lower 唯一、UDI-DI 非空唯一），服务层先查重返回
 * 409；更新使用乐观锁（{@code WHERE version = ?} + {@code version = version + 1}）。
 */
@Mapper
public interface MaterialMapper extends BaseMapper<MaterialEntity> {

    @Insert("""
            INSERT INTO m4_material.material
                (id, code, name, specification, unit, category, udi_di, high_value,
                 expiry_managed, status, remark, created_at, created_by, updated_at,
                 updated_by, version)
            VALUES
                (#{id}, #{code}, #{name}, #{specification}, #{unit}, #{category}, #{udiDi},
                 #{highValue}, #{expiryManaged}, #{status}, #{remark}, #{createdAt},
                 #{createdBy}, #{updatedAt}, #{updatedBy}, #{version})
            """)
    int insertMaterial(MaterialEntity material);

    /** 乐观锁更新：编码不可改；版本过期时影响行数为 0（服务层 409）。 */
    @Update("""
            UPDATE m4_material.material
               SET name = #{name},
                   specification = #{specification},
                   unit = #{unit},
                   category = #{category},
                   udi_di = #{udiDi},
                   high_value = #{highValue},
                   expiry_managed = #{expiryManaged},
                   status = #{status},
                   remark = #{remark},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int updateMaterial(MaterialEntity material);

    @Select("SELECT * FROM m4_material.material WHERE id = #{id}")
    MaterialEntity selectByMaterialId(@Param("id") UUID id);

    /** 启用/停用：带乐观锁版本条件；状态未变化由服务层幂等返回（不调用本方法）。 */
    @Update("""
            UPDATE m4_material.material
               SET status = #{status},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status,
            @Param("version") int version, @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") UUID updatedBy);

    @Select("""
            <script>
            SELECT * FROM m4_material.material
            <where>
              <if test="keyword != null and keyword != ''">
                (lower(name) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(code) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(coalesce(udi_di, '')) LIKE lower(concat('%', #{keyword}, '%')))
              </if>
              <if test="status != null and status != ''">
                AND status = #{status}
              </if>
              <if test="highValue != null">
                AND high_value = #{highValue}
              </if>
            </where>
            ORDER BY name
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MaterialEntity> selectPage(@Param("keyword") String keyword,
            @Param("status") String status, @Param("highValue") Boolean highValue,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT count(*) FROM m4_material.material
            <where>
              <if test="keyword != null and keyword != ''">
                (lower(name) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(code) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(coalesce(udi_di, '')) LIKE lower(concat('%', #{keyword}, '%')))
              </if>
              <if test="status != null and status != ''">
                AND status = #{status}
              </if>
              <if test="highValue != null">
                AND high_value = #{highValue}
              </if>
            </where>
            </script>
            """)
    long countPage(@Param("keyword") String keyword, @Param("status") String status,
            @Param("highValue") Boolean highValue);

    /** 扫码/录入联想：只返回启用耗材，按“编码/UDI-DI 精确优先、名称”排序。 */
    @Select("""
            SELECT * FROM m4_material.material
             WHERE status = 'ACTIVE'
               AND (lower(name) LIKE lower(concat('%', #{q}, '%'))
                    OR lower(code) LIKE lower(concat('%', #{q}, '%'))
                    OR lower(coalesce(udi_di, '')) LIKE lower(concat('%', #{q}, '%')))
             ORDER BY CASE WHEN lower(code) = lower(#{q})
                             OR lower(coalesce(udi_di, '')) = lower(#{q}) THEN 0 ELSE 1 END,
                      name
             LIMIT #{limit}
            """)
    List<MaterialEntity> searchActive(@Param("q") String q, @Param("limit") int limit);

    @Select("""
            SELECT count(*) FROM m4_material.material
             WHERE lower(btrim(code)) = lower(btrim(#{code})) AND id <> #{excludeId}::uuid
            """)
    long countCodeConflict(@Param("code") String code, @Param("excludeId") String excludeId);

    @Select("""
            SELECT count(*) FROM m4_material.material
             WHERE lower(btrim(name)) = lower(btrim(#{name})) AND id <> #{excludeId}::uuid
            """)
    long countNameConflict(@Param("name") String name, @Param("excludeId") String excludeId);

    @Select("""
            SELECT count(*) FROM m4_material.material
             WHERE lower(btrim(udi_di)) = lower(btrim(#{udiDi})) AND id <> #{excludeId}::uuid
            """)
    long countUdiDiConflict(@Param("udiDi") String udiDi, @Param("excludeId") String excludeId);
}
