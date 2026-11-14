package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.DailyCloseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 麻精日结/班结 Mapper（{@code m4_drug.daily_close}，V20）。
 *
 * <p>append-only：只 INSERT/查询；平账公式由数据库 CHECK 兜底（服务层先返回 409）。
 */
@Mapper
public interface DailyCloseMapper extends BaseMapper<DailyCloseEntity> {

    @Insert("""
            INSERT INTO m4_drug.daily_close
                (id, close_date, shift, cabinet_code, department_id, opening_quantity,
                 inbound_quantity, used_quantity, returned_quantity, closing_quantity,
                 created_at, created_by)
            VALUES
                (#{id}, #{closeDate}, #{shift}, #{cabinetCode}, #{departmentId},
                 #{openingQuantity}, #{inboundQuantity}, #{usedQuantity}, #{returnedQuantity},
                 #{closingQuantity}, #{createdAt}, #{createdBy})
            """)
    int insertClose(DailyCloseEntity close);

    @Select("SELECT * FROM m4_drug.daily_close WHERE id = #{id}")
    DailyCloseEntity selectByCloseId(@Param("id") UUID id);

    @Select("""
            SELECT count(*) FROM m4_drug.daily_close
             WHERE cabinet_code = #{cabinetCode}
               AND close_date = #{closeDate}
               AND shift = #{shift}
            """)
    long countDuplicate(@Param("cabinetCode") String cabinetCode,
            @Param("closeDate") LocalDate closeDate, @Param("shift") String shift);
}
