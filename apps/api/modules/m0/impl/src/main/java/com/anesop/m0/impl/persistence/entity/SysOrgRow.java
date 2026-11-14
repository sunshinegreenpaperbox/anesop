package com.anesop.m0.impl.persistence.entity;

import com.anesop.m0.api.security.OrgDepartment;
import java.util.UUID;

/**
 * 组织只读行（{@code m0_platform.sys_org}），供科室选择/校验查询使用。
 *
 * <p>只映射 id/code/name 三列；不承载地址、联系方式等扩展字段。
 */
public class SysOrgRow {

    private UUID id;
    private String code;
    private String name;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** 转为跨模块只读视图。 */
    public OrgDepartment toDepartment() {
        return new OrgDepartment(id == null ? null : id.toString(), code, name);
    }
}
