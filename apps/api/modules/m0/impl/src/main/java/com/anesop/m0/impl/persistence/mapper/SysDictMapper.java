package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysDictType;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字典类型 Mapper（m0_platform.sys_dict_type）。
 *
 * <p>字典项查询见 {@link SysDictItemMapper}。
 */
@Mapper
public interface SysDictMapper extends BaseMapper<SysDictType> {
}
