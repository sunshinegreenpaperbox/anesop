package com.anesop.m0.impl.persistence.entity;

import com.anesop.m0.impl.persistence.handler.InetTypeHandler;
import com.anesop.m0.impl.persistence.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 审计日志实体，对应 m0_platform.sys_audit_log（V1）。
 *
 * <p>append-only：表中无 updated_at / deleted；数据库触发器拒绝 UPDATE/DELETE/TRUNCATE。
 * 本实体只用于 INSERT 与查询，Mapper 层不得暴露更新/删除入口。
 */
@TableName("m0_platform.sys_audit_log")
public class SysAuditLog {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID actorId;

    private String actorName;

    private String action;

    private String resourceType;

    private String resourceId;

    private Integer result;

    /** 变更明细 JSONB，以字符串承载；写入/读取由 {@link JsonbTypeHandler} 映射为 jsonb。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String detail;

    /** 来源 IP；写入/读取由 {@link InetTypeHandler} 映射为 inet（IPv4/IPv6）。 */
    @TableField(typeHandler = InetTypeHandler.class)
    private String ip;

    private String userAgent;

    private String traceId;

    private Instant occurredAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public Integer getResult() {
        return result;
    }

    public void setResult(Integer result) {
        this.result = result;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
