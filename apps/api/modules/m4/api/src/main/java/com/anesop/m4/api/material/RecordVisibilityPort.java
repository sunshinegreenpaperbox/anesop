package com.anesop.m4.api.material;

import com.anesop.m0.api.security.AuthPrincipal;
import java.util.Optional;

/**
 * 麻醉记录可见性端口（M4-M14-01 依赖倒置）。
 *
 * <p>UDI 使用需要校验记录存在且对调用者可见，但 m2 已依赖 m4-api（药品字典），若 m4-impl
 * 反向依赖 m2-api 会形成模块循环（ArchUnit 禁止）。因此本接口声明在 m4-api，由装配层
 * （api-bootstrap）用 m2-api 实现并注入，m4-impl 只依赖本接口。
 *
 * <p>语义：记录不存在、越界或不可见一律返回 {@link Optional#empty()}（fail-closed），
 * 由调用方映射 422，不泄露记录存在性。返回值只含记录/台次标识，不含患者信息。
 */
public interface RecordVisibilityPort {

    /** 记录与台次的逻辑引用（仅标识）。 */
    record RecordReference(String recordId, String scheduleId) {
    }

    /**
     * 查询调用者可见的麻醉记录。
     *
     * @param recordId 记录标识
     * @param caller   调用者
     * @return 可见记录引用；不存在/不可见返回 empty
     */
    Optional<RecordReference> findVisibleRecord(String recordId, AuthPrincipal caller);
}
