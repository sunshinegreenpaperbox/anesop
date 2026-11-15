package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.platform.model.Message;
import com.anesop.contracts.platform.model.MessagePage;
import com.anesop.contracts.platform.model.MessageReadAllResponse;
import com.anesop.m0.api.platform.MessageService;
import com.anesop.m0.api.security.AuthPermissions;
import com.anesop.m0.api.security.AuthRequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内消息接口（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>授权：需 {@code m0:message:read}；服务层强制 {@code recipient_user_id = 当前用户}，
 * 非本人消息按 404 处理（不泄露他人消息是否存在）。**没有**“给任意用户发消息”的端点：
 * 消息由领域事件/系统广播经内部 {@code MessagePublisher} 写入。
 */
@RestController
@RequestMapping("/v1/messages")
@Tag(name = "消息中心", description = "当前用户站内消息分页、单条已读与全部已读（仅本人可见）")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_MESSAGE_READ + "')")
    @Operation(operationId = "listMessages", summary = "查询当前用户消息（分页、可按未读过滤）")
    public MessagePage list(@RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @AuthenticationPrincipal Jwt jwt) {
        return messageService.list(AuthPrincipals.from(jwt), page, size, unreadOnly);
    }

    @PostMapping("/{messageId}/read")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_MESSAGE_READ + "')")
    @Operation(operationId = "markMessageRead", summary = "标记单条消息已读（幂等）")
    public Message markRead(@PathVariable("messageId") UUID messageId,
            @AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        return messageService.markRead(messageId, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/read-all")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_MESSAGE_READ + "')")
    @Operation(operationId = "markAllMessagesRead", summary = "标记当前用户全部消息已读")
    public MessageReadAllResponse readAll(@AuthenticationPrincipal Jwt jwt,
            AuthRequestContext context) {
        int updated = messageService.markAllRead(AuthPrincipals.from(jwt), context);
        MessageReadAllResponse response = new MessageReadAllResponse();
        response.setUpdatedCount(updated);
        return response;
    }
}
