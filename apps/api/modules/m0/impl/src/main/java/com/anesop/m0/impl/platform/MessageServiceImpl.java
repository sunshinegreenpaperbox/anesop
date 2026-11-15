package com.anesop.m0.impl.platform;

import com.anesop.contracts.platform.model.Message;
import com.anesop.contracts.platform.model.MessagePage;
import com.anesop.contracts.platform.model.MessageType;
import com.anesop.contracts.platform.model.PageMeta;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.platform.M0ValidationException;
import com.anesop.m0.api.platform.MessagePublisher;
import com.anesop.m0.api.platform.MessageService;
import com.anesop.m0.api.platform.MessageTemplates;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.impl.persistence.entity.SysMessage;
import com.anesop.m0.impl.persistence.mapper.SysMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内消息服务实现（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>同时实现 {@link MessagePublisher}（内部写入入口）：消息由领域事件/系统广播写入，
 * HTTP 层**没有**任意发送端点。标题与参数白名单由 {@link MessageTemplates} 注册表决定，
 * 调用方不能提交任意标题；参数值 ≤200 字符且不含控制字符。参数禁止患者标识与临床
 * 自由文本（评审责任在调用方，本类做结构/长度/字符集兜底）。
 *
 * <p>读取与已读一律强制 {@code recipient_user_id = 当前用户}；非本人消息按 404 处理，
 * 不泄露他人消息是否存在。未读数 {@code unreadCount} 始终是全部未读数（菜单角标）。
 */
@Service
public class MessageServiceImpl implements MessageService, MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(MessageServiceImpl.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PARAM_VALUE_LENGTH = 200;
    private static final String ACTION_READ = "MESSAGE_READ";
    private static final String ACTION_READ_ALL = "MESSAGE_READ_ALL";
    private static final String RESOURCE_TYPE = "MESSAGE";
    private static final Pattern TEMPLATE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final TypeReference<Map<String, String>> PARAM_TYPE = new TypeReference<>() {
    };

    private final SysMessageMapper messageMapper;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MessageServiceImpl(SysMessageMapper messageMapper, AuditWriter auditWriter,
            ObjectMapper objectMapper, Clock authClock) {
        this.messageMapper = messageMapper;
        this.auditWriter = auditWriter;
        // 防御性拷贝：不持有外部可变 ObjectMapper（SpotBugs EI_EXPOSE_REP2）。
        this.objectMapper = objectMapper.copy();
        this.clock = authClock;
    }

    @Override
    @Transactional(readOnly = true)
    public MessagePage list(AuthPrincipal caller, int page, int size, boolean unreadOnly) {
        UUID recipient = requireCaller(caller);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));

        QueryWrapper<SysMessage> wrapper = new QueryWrapper<SysMessage>().eq("recipient_user_id", recipient);
        if (unreadOnly) {
            wrapper.isNull("read_at");
        }
        Long counted = messageMapper.selectCount(wrapper);
        long total = counted == null ? 0 : counted;
        long offset = (long) (safePage - 1) * safeSize;

        List<Message> items = new ArrayList<>();
        if (total > offset) {
            List<SysMessage> rows = messageMapper.selectList(new QueryWrapper<SysMessage>()
                    .eq("recipient_user_id", recipient)
                    .isNull(unreadOnly, "read_at")
                    .orderByDesc("created_at")
                    .orderByDesc("id")
                    .last("LIMIT " + safeSize + " OFFSET " + offset));
            for (SysMessage row : rows) {
                items.add(toModel(row));
            }
        }

        PageMeta meta = new PageMeta();
        meta.setPage(safePage);
        meta.setSize(safeSize);
        meta.setTotal((int) Math.min(Integer.MAX_VALUE, total));
        meta.setTotalPages(total == 0 ? 0
                : (int) Math.min(Integer.MAX_VALUE, (total + safeSize - 1) / safeSize));

        Long unread = messageMapper.selectCount(new QueryWrapper<SysMessage>()
                .eq("recipient_user_id", recipient)
                .isNull("read_at"));

        MessagePage result = new MessagePage();
        result.setItems(items);
        result.setMeta(meta);
        result.setUnreadCount(unread == null ? 0 : Math.toIntExact(unread));
        return result;
    }

    @Override
    @Transactional
    public Message markRead(UUID messageId, AuthPrincipal caller, AuthRequestContext context) {
        UUID recipient = requireCaller(caller);
        SysMessage row = findOwned(messageId, recipient);
        if (row.getReadAt() == null) {
            row.setReadAt(Instant.now(clock));
            messageMapper.updateById(row);
        }
        audit(ACTION_READ, row, caller, context);
        return toModel(row);
    }

    @Override
    @Transactional
    public int markAllRead(AuthPrincipal caller, AuthRequestContext context) {
        UUID recipient = requireCaller(caller);
        int updated = messageMapper.update(null, new UpdateWrapper<SysMessage>()
                .eq("recipient_user_id", recipient)
                .isNull("read_at")
                .set("read_at", Instant.now(clock)));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("updatedCount", updated);
        auditWriter.record(ACTION_READ_ALL, RESOURCE_TYPE, recipient.toString(), true, detail,
                caller.userId(), caller.username(), context);
        return updated;
    }

    @Override
    @Transactional
    public UUID publish(MessageCommand command) {
        if (command == null || command.recipientUserId() == null) {
            throw new M0ValidationException("recipientUserId", "消息收件人不能为空");
        }
        if (command.type() == null) {
            throw new M0ValidationException("type", "消息类型不能为空");
        }
        String templateCode = command.templateCode() == null ? "" : command.templateCode().trim();
        if (!TEMPLATE_CODE.matcher(templateCode).matches()) {
            throw new M0ValidationException("templateCode",
                    "模板编码只允许大写字母/数字/下划线，长度 1–64");
        }
        MessageTemplates.Template template = MessageTemplates.find(templateCode)
                .orElseThrow(() -> new M0ValidationException("templateCode",
                        "模板未在服务端注册，禁止任意文案"));
        if (template.type() != command.type()) {
            throw new M0ValidationException("type", "消息类型与模板注册类型不一致");
        }
        Map<String, String> params = validateParams(template, command.params());

        SysMessage row = new SysMessage();
        row.setId(UUID.randomUUID());
        row.setRecipientUserId(command.recipientUserId());
        row.setMessageType(template.type().getValue());
        row.setTemplateCode(template.code());
        // 标题只来自服务端注册表，调用方无法注入任意文案或 PHI（评审 S-4）。
        row.setTitle(template.title());
        row.setParams(toJson(params));
        row.setCreatedAt(Instant.now(clock));
        messageMapper.insert(row);
        return row.getId();
    }

    private static Map<String, String> validateParams(MessageTemplates.Template template,
            Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || !template.allowedParams().contains(key)) {
                throw new M0ValidationException("params", "消息参数键不在该模板允许范围内");
            }
            String value = entry.getValue();
            if (value == null || value.isBlank() || value.length() > MAX_PARAM_VALUE_LENGTH
                    || containsControlChar(value)) {
                throw new M0ValidationException("params", "消息参数值长度必须为 1–200 字符且不含控制字符");
            }
            safe.put(key, value);
        }
        return safe;
    }

    private static boolean containsControlChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private SysMessage findOwned(UUID messageId, UUID recipient) {
        if (messageId == null) {
            throw new M0ValidationException("messageId", "消息标识不能为空");
        }
        SysMessage row = messageMapper.selectOne(new QueryWrapper<SysMessage>()
                .eq("id", messageId)
                .eq("recipient_user_id", recipient)
                .last("LIMIT 1"));
        if (row == null) {
            // 非本人与不存在统一 404，避免通过错误码探测他人消息。
            throw new NoSuchElementException("消息不存在或不可见");
        }
        return row;
    }

    private static UUID requireCaller(AuthPrincipal caller) {
        if (caller == null || caller.userId() == null) {
            throw new NoSuchElementException("消息不存在或不可见");
        }
        return caller.userId();
    }

    private void audit(String action, SysMessage row, AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("messageType", row.getMessageType());
        detail.put("templateCode", row.getTemplateCode());
        auditWriter.record(action, RESOURCE_TYPE, row.getId().toString(), true, detail,
                caller.userId(), caller.username(), context);
    }

    private String toJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            // 参数只含字符串键值，序列化失败属编程错误；降级为空对象并记录（不含参数值）。
            LOG.error("消息参数序列化失败，降级为空对象", e);
            return "{}";
        }
    }

    private Map<String, String> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, PARAM_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            // 历史脏数据不应导致消息列表 500；记录 WARN 并返回空参数（不含原始值）。
            LOG.warn("消息参数解析失败，按空参数返回：messageId 已脱敏");
            return Map.of();
        }
    }

    private Message toModel(SysMessage row) {
        Message model = new Message();
        model.setMessageId(row.getId());
        MessageType type = MessageType.fromValue(row.getMessageType());
        model.setType(type == null ? MessageType.SYSTEM : type);
        model.setTemplateCode(row.getTemplateCode());
        model.setTitle(row.getTitle());
        model.setParams(parseParams(row.getParams()));
        model.setRead(row.getReadAt() != null);
        model.setCreatedAt(offset(row.getCreatedAt()));
        model.setReadAt(offset(row.getReadAt()));
        return model;
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZONE);
    }
}
