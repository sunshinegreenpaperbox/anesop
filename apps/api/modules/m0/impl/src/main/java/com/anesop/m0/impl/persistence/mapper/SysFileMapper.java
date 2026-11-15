package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件元数据 Mapper（m0_platform.sys_file，V27）。
 *
 * <p>逻辑删除由 MyBatis-Plus 全局 {@code deleted} 配置处理；文件内容不经本 Mapper 存储。
 */
@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {
}
