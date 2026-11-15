package com.anesop.m0.api.platform;

import com.anesop.contracts.platform.model.CreateDictItemRequest;
import com.anesop.contracts.platform.model.DictItem;
import com.anesop.contracts.platform.model.DictItemList;
import com.anesop.contracts.platform.model.DictTypeList;
import com.anesop.contracts.platform.model.UpdateDictItemRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.util.Set;
import java.util.UUID;

/**
 * 字典服务（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>口径：
 * <ul>
 *   <li><b>全院读</b>：字典是全院共享参考数据，{@link #listTypes()} 与
 *       {@link #listItems(String, boolean, boolean)} 不做数据范围裁剪；</li>
 *   <li><b>管理仅管理员</b>：写方法除 Web 层 {@code m0:dict:write} 外，服务层再校验
 *       {@code SYS_ADMIN} 角色（纵深防御，避免后续误授权导致非管理员改字典）；</li>
 *   <li><b>编码不可变 + 逻辑停用</b>：{@code code} 创建后不变；停用只置
 *       {@code enabled=false}，不物理删除，保证历史记录按当时版本可解释；</li>
 *   <li><b>无 PHI</b>：字典项名称/备注只放口径说明，禁止患者姓名、住院号、证件号、手机号
 *       与临床自由文本。</li>
 * </ul>
 */
public interface DictService {

    /** 查询全部启用字典类型及启用项数量（按类型编码升序）。 */
    DictTypeList listTypes();

    /**
     * 按类型编码查询字典项。
     *
     * @param type            字典类型编码
     * @param includeDisabled 是否包含停用项
     * @param canManage       调用方是否持 {@code m0:dict:write}（false 时 includeDisabled=true → 403）
     * @throws java.util.NoSuchElementException 类型不存在或已停用
     * @throws org.springframework.security.access.AccessDeniedException 普通用户请求停用项
     */
    DictItemList listItems(String type, boolean includeDisabled, boolean canManage);

    /**
     * 新增字典项；同类型内编码重复抛 {@link M0ConflictException}。
     *
     * @param roles 调用方角色码（JWT {@code roles} claim）；非 {@code SYS_ADMIN} 抛
     *              {@link org.springframework.security.access.AccessDeniedException}（403）
     */
    DictItem createItem(String type, CreateDictItemRequest request, AuthPrincipal caller,
            Set<String> roles, AuthRequestContext context);

    /**
     * 更新字典项名称/排序/启停/备注（编码不可改）。
     *
     * @throws java.util.NoSuchElementException 类型或字典项不存在
     * @throws org.springframework.security.access.AccessDeniedException 非 {@code SYS_ADMIN}
     */
    DictItem updateItem(String type, UUID itemId, UpdateDictItemRequest request, AuthPrincipal caller,
            Set<String> roles, AuthRequestContext context);

    /**
     * 停用字典项（逻辑停用、幂等）。
     *
     * @throws java.util.NoSuchElementException 类型或字典项不存在
     * @throws org.springframework.security.access.AccessDeniedException 非 {@code SYS_ADMIN}
     */
    void disableItem(String type, UUID itemId, AuthPrincipal caller, Set<String> roles,
            AuthRequestContext context);
}
