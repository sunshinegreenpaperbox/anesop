package com.anesop.m1.impl.persistence.mapper;

import com.anesop.m1.impl.persistence.entity.OperatingRoomEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 术间 Mapper（{@code m1_sched.operating_room}）。
 *
 * <p>{@link #selectByIdForUpdate} 在排程事务内锁定术间行：同一术间的并发创建/调整被串行化，
 * 后到事务在锁释放后复检重叠，保证同一术间同一时段恰有一个台次成功（M2-M2-01 并发策略）。
 */
@Mapper
public interface OperatingRoomMapper extends BaseMapper<OperatingRoomEntity> {

    /**
     * 事务内锁定术间行（{@code SELECT ... FOR UPDATE}）。
     *
     * @param id 术间 UUID
     * @return 术间行；不存在或已软删除返回 null
     */
    @Select("""
            SELECT * FROM m1_sched.operating_room
            WHERE id = #{id} AND deleted = false
            FOR UPDATE
            """)
    OperatingRoomEntity selectByIdForUpdate(UUID id);
}
