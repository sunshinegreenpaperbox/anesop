package com.anesop.m1.impl.persistence.mapper;

import com.anesop.m1.impl.persistence.entity.ApplicationStatusHistoryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 手术申请状态历史 Mapper（{@code m1_sched.application_status_history}）。
 *
 * <p>append-only：只允许 insert 与按申请查询，不暴露 update/delete。
 */
@Mapper
public interface ApplicationStatusHistoryMapper extends BaseMapper<ApplicationStatusHistoryEntity> {
}
