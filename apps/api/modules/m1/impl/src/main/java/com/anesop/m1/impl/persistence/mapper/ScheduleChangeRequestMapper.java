package com.anesop.m1.impl.persistence.mapper;

import com.anesop.m1.impl.persistence.entity.ScheduleChangeRequestEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 台次变更记录 Mapper（{@code m1_sched.schedule_change_request}，V9）。
 *
 * <p>只提供 INSERT/查询：变更留痕 append-only，不提供更新/删除的业务入口；
 * 抢占关系与审批人字段由排程服务在同一事务内写入。
 */
@Mapper
public interface ScheduleChangeRequestMapper extends BaseMapper<ScheduleChangeRequestEntity> {
}
