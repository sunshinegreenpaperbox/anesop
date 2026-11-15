package com.anesop.m0.impl.platform;

import com.anesop.contracts.platform.model.CreateDictItemRequest;
import com.anesop.contracts.platform.model.DictItem;
import com.anesop.contracts.platform.model.DictItemList;
import com.anesop.contracts.platform.model.DictType;
import com.anesop.contracts.platform.model.DictTypeList;
import com.anesop.contracts.platform.model.UpdateDictItemRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.platform.DictService;
import com.anesop.m0.api.platform.M0ConflictException;
import com.anesop.m0.api.platform.M0ValidationException;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.impl.persistence.entity.SysDictItem;
import com.anesop.m0.impl.persistence.entity.SysDictType;
import com.anesop.m0.impl.persistence.mapper.SysDictItemMapper;
import com.anesop.m0.impl.persistence.mapper.SysDictMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字典服务实现（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>权限与口径：
 * <ul>
 *   <li>读：全院可读（Web 层 {@code m0:dict:read}），不做数据范围裁剪；</li>
 *   <li>写：Web 层 {@code m0:dict:write}，本实现再校验 {@code SYS_ADMIN} 角色
 *       （纵深防御，防止后续误把写权限授予业务角色）；</li>
 *   <li>{@code includeDisabled=true} 只对持 {@code m0:dict:write} 的管理员开放，
 *       普通用户请求停用项返回 403；</li>
 *   <li>编码不可变：更新只改 name/sort/status/remark；停用为逻辑停用（status=0），不物理删除。</li>
 * </ul>
 *
 * <p>审计：{@code DICT_ITEM_CREATE}/{@code DICT_ITEM_UPDATE}/{@code DICT_ITEM_DISABLE}，
 * detail 只含字典类型/编码/排序/启停等非 PHI 字段。
 */
@Service
public class DictServiceImpl implements DictService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ACTION_CREATE = "DICT_ITEM_CREATE";
    private static final String ACTION_UPDATE = "DICT_ITEM_UPDATE";
    private static final String ACTION_DISABLE = "DICT_ITEM_DISABLE";
    private static final String RESOURCE_TYPE = "DICT_ITEM";
    private static final String ROLE_ADMIN = "SYS_ADMIN";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_REMARK_LENGTH = 255;
    private static final int MAX_SORT = 9999;
    private static final int ENABLED = 1;
    private static final int DISABLED = 0;

    private final SysDictMapper dictMapper;
    private final SysDictItemMapper itemMapper;
    private final AuditWriter auditWriter;
    private final Clock clock;

    public DictServiceImpl(SysDictMapper dictMapper, SysDictItemMapper itemMapper,
            AuditWriter auditWriter, Clock authClock) {
        this.dictMapper = dictMapper;
        this.itemMapper = itemMapper;
        this.auditWriter = auditWriter;
        this.clock = authClock;
    }

    @Override
    @Transactional(readOnly = true)
    public DictTypeList listTypes() {
        List<SysDictType> types = dictMapper.selectList(new QueryWrapper<SysDictType>()
                .eq("status", ENABLED)
                .orderByAsc("code"));

        List<UUID> typeIds = new ArrayList<>();
        for (SysDictType type : types) {
            typeIds.add(type.getId());
        }
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        if (!typeIds.isEmpty()) {
            List<SysDictItem> enabledItems = itemMapper.selectList(new QueryWrapper<SysDictItem>()
                    .in("dict_type_id", typeIds)
                    .eq("status", ENABLED));
            for (SysDictItem item : enabledItems) {
                counts.merge(item.getDictTypeId(), 1, Integer::sum);
            }
        }

        List<DictType> items = new ArrayList<>();
        for (SysDictType type : types) {
            DictType model = new DictType();
            model.setType(type.getCode());
            model.setName(type.getName());
            model.setDescription(type.getDescription());
            model.setItemCount(counts.getOrDefault(type.getId(), 0));
            model.setUpdatedAt(offset(type.getUpdatedAt()));
            items.add(model);
        }
        DictTypeList result = new DictTypeList();
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public DictItemList listItems(String type, boolean includeDisabled, boolean canManage) {
        if (includeDisabled && !canManage) {
            // 停用语义只对管理员暴露；普通用户视为“无权查看停用项”，不是“没有数据”。
            throw new AccessDeniedException("仅管理员可查看停用字典项");
        }
        SysDictType dictType = requireType(type);

        QueryWrapper<SysDictItem> wrapper = new QueryWrapper<SysDictItem>()
                .eq("dict_type_id", dictType.getId())
                .orderByAsc("sort")
                .orderByAsc("code");
        if (!includeDisabled) {
            wrapper.eq("status", ENABLED);
        }
        List<SysDictItem> rows = itemMapper.selectList(wrapper);

        List<DictItem> items = new ArrayList<>();
        for (SysDictItem row : rows) {
            items.add(toModel(dictType.getCode(), row));
        }
        DictItemList result = new DictItemList();
        result.setType(dictType.getCode());
        result.setName(dictType.getName());
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional
    public DictItem createItem(String type, CreateDictItemRequest request, AuthPrincipal caller,
            Set<String> roles, AuthRequestContext context) {
        requireAdmin(roles);
        SysDictType dictType = requireType(type);
        String code = validateCode(request.getCode());
        String name = validateName(request.getName());
        int sort = validateSort(request.getSort());
        String remark = validateRemark(request.getRemark());
        boolean enabled = request.getEnabled() == null || request.getEnabled();

        Long existing = itemMapper.selectCount(new QueryWrapper<SysDictItem>()
                .eq("dict_type_id", dictType.getId())
                .eq("code", code));
        if (existing != null && existing > 0) {
            throw new M0ConflictException("字典项编码已存在或数据已被修改");
        }

        Instant now = Instant.now(clock);
        SysDictItem row = new SysDictItem();
        row.setId(UUID.randomUUID());
        row.setDictTypeId(dictType.getId());
        row.setCode(code);
        row.setLabel(name);
        row.setSort(sort);
        row.setStatus(enabled ? ENABLED : DISABLED);
        row.setRemark(remark);
        row.setCreatedAt(now);
        row.setCreatedBy(caller == null ? null : caller.userId());
        row.setUpdatedAt(now);
        row.setUpdatedBy(caller == null ? null : caller.userId());
        itemMapper.insert(row);

        audit(ACTION_CREATE, row.getId().toString(), dictType.getCode(), code, sort, enabled,
                caller, context);
        return toModel(dictType.getCode(), row);
    }

    @Override
    @Transactional
    public DictItem updateItem(String type, UUID itemId, UpdateDictItemRequest request,
            AuthPrincipal caller, Set<String> roles, AuthRequestContext context) {
        requireAdmin(roles);
        SysDictType dictType = requireType(type);
        SysDictItem row = requireItem(dictType, itemId);

        String name = validateName(request.getName());
        int sort = validateSort(request.getSort());
        String remark = validateRemark(request.getRemark());
        boolean enabled = request.getEnabled() == null || request.getEnabled();

        row.setLabel(name);
        row.setSort(sort);
        row.setStatus(enabled ? ENABLED : DISABLED);
        row.setRemark(remark);
        row.setUpdatedAt(Instant.now(clock));
        row.setUpdatedBy(caller == null ? null : caller.userId());
        itemMapper.updateById(row);

        audit(ACTION_UPDATE, row.getId().toString(), dictType.getCode(), row.getCode(), sort, enabled,
                caller, context);
        return toModel(dictType.getCode(), row);
    }

    @Override
    @Transactional
    public void disableItem(String type, UUID itemId, AuthPrincipal caller, Set<String> roles,
            AuthRequestContext context) {
        requireAdmin(roles);
        SysDictType dictType = requireType(type);
        SysDictItem row = requireItem(dictType, itemId);
        // 逻辑停用、幂等：已停用仍返回 204，但保留首次 updatedAt/updatedBy 语义。
        if (row.getStatus() == null || row.getStatus() != DISABLED) {
            row.setStatus(DISABLED);
            row.setUpdatedAt(Instant.now(clock));
            row.setUpdatedBy(caller == null ? null : caller.userId());
            itemMapper.updateById(row);
        }
        audit(ACTION_DISABLE, row.getId().toString(), dictType.getCode(), row.getCode(),
                row.getSort(), false, caller, context);
    }

    private SysDictType requireType(String type) {
        String code = type == null ? "" : type.trim();
        if (code.isEmpty()) {
            throw new M0ValidationException("type", "字典类型编码不能为空");
        }
        SysDictType dictType = dictMapper.selectOne(new QueryWrapper<SysDictType>()
                .eq("code", code)
                .eq("status", ENABLED)
                .last("LIMIT 1"));
        if (dictType == null) {
            throw new NoSuchElementException("字典类型不存在或已停用");
        }
        return dictType;
    }

    private SysDictItem requireItem(SysDictType dictType, UUID itemId) {
        if (itemId == null) {
            throw new M0ValidationException("itemId", "字典项标识不能为空");
        }
        SysDictItem row = itemMapper.selectOne(new QueryWrapper<SysDictItem>()
                .eq("id", itemId)
                .eq("dict_type_id", dictType.getId())
                .last("LIMIT 1"));
        if (row == null) {
            throw new NoSuchElementException("字典项不存在或不属于该字典类型");
        }
        return row;
    }

    private static void requireAdmin(Set<String> roles) {
        if (roles == null || !roles.contains(ROLE_ADMIN)) {
            throw new AccessDeniedException("字典维护仅限平台管理员");
        }
    }

    private static String validateCode(String code) {
        String value = code == null ? "" : code.trim();
        if (!CODE_PATTERN.matcher(value).matches()) {
            throw new M0ValidationException("code",
                    "字典项编码只允许字母/数字/下划线/点/短横线，长度 1–64");
        }
        return value;
    }

    private static String validateName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > MAX_NAME_LENGTH) {
            throw new M0ValidationException("name", "字典项名称长度必须为 1–100 字符");
        }
        return value;
    }

    private static int validateSort(Integer sort) {
        int value = sort == null ? 0 : sort;
        if (value < 0 || value > MAX_SORT) {
            throw new M0ValidationException("sort", "排序号必须为 0–9999");
        }
        return value;
    }

    private static String validateRemark(String remark) {
        if (remark == null) {
            return null;
        }
        String value = remark.trim();
        if (value.length() > MAX_REMARK_LENGTH) {
            throw new M0ValidationException("remark", "备注长度不能超过 255 字符");
        }
        return value.isEmpty() ? null : value;
    }

    private void audit(String action, String resourceId, String dictType, String code, int sort,
            boolean enabled, AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("dictType", dictType);
        detail.put("itemCode", code);
        detail.put("sort", sort);
        detail.put("enabled", enabled);
        auditWriter.record(action, RESOURCE_TYPE, resourceId, true, detail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    private static DictItem toModel(String type, SysDictItem row) {
        DictItem model = new DictItem();
        model.setItemId(row.getId());
        model.setType(type);
        model.setCode(row.getCode());
        model.setName(row.getLabel());
        Integer sort = row.getSort();
        model.setSort(sort == null ? Integer.valueOf(0) : sort);
        model.setEnabled(row.getStatus() != null && row.getStatus() == ENABLED);
        model.setRemark(row.getRemark());
        model.setUpdatedAt(offset(row.getUpdatedAt()));
        return model;
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZONE);
    }
}
