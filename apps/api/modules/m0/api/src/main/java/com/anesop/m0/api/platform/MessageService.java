package com.anesop.m0.api.platform;

import com.anesop.contracts.platform.model.Message;
import com.anesop.contracts.platform.model.MessagePage;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.util.UUID;

/**
 * 站内消息服务（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>硬规则：
 * <ul>
 *   <li><b>仅本人</b>：所有查询/已读操作强制 {@code recipient_user_id = 当前用户}；
 *       非本人消息与不存在统一视为不可见（404），不泄露他人消息是否存在；</li>
 *   <li><b>无任意发送端点</b>：消息只能由 {@link MessagePublisher}（领域事件/系统广播）
 *       写入，HTTP 层不暴露“给任意用户发消息”的接口；</li>
 *   <li><b>无 PHI</b>：消息为模板编码 + 参数白名单，禁止患者标识与临床自由文本；</li>
 *   <li><b>已读幂等</b>：重复标记已读不改变首次 {@code readAt}，不物理删除消息。</li>
 * </ul>
 */
public interface MessageService {

    /**
     * 查询当前用户消息分页（创建时间倒序）。
     *
     * @param page       页码（≥1，越界夹紧）
     * @param size       每页条数（1–100，越界夹紧）
     * @param unreadOnly 是否只返回未读
     */
    MessagePage list(AuthPrincipal caller, int page, int size, boolean unreadOnly);

    /**
     * 标记单条消息已读（幂等）。
     *
     * @throws java.util.NoSuchElementException 消息不存在或不属于当前用户（404，不区分两者）
     */
    Message markRead(UUID messageId, AuthPrincipal caller, AuthRequestContext context);

    /** 标记当前用户全部未读消息已读，返回本次更新条数。 */
    int markAllRead(AuthPrincipal caller, AuthRequestContext context);
}
