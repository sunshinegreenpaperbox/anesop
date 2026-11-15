package com.anesop.m0.impl.persistence.entity;

import com.anesop.m0.impl.persistence.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 站内消息实体，对应 m0_platform.sys_message（V27，M8-M0-04）。
 *
 * <p>仅收件人本人可见；由领域事件/系统广播写入（{@code MessagePublisher}）。
 * {@code params} 只存模板参数白名单（jsonb），禁止患者标识与临床自由文本；
 * {@code readAt} 为空表示未读，已读为幂等更新，消息不物理删除。
 */
@TableName("m0_platform.sys_message")
public class SysMessage {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID recipientUserId;

    private String messageType;

    private String templateCode;

    private String title;

    /** 模板参数 JSONB，以字符串承载；写入/读取由 {@link JsonbTypeHandler} 映射为 jsonb。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String params;

    /**
     * 业务投递去重键（V29，M9-01）：OUTBOX:<outboxId>:<recipient>:<templateCode>，无 PHI。
     * 唯一索引保证同一业务事件+接收人+模板只落一条；NULL 表示不去重。
     */
    private String dedupeKey;

    private Instant readAt;

    private Instant createdAt;

    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRecipientUserId() {
        return recipientUserId;
    }

    public void setRecipientUserId(UUID recipientUserId) {
        this.recipientUserId = recipientUserId;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }
}
