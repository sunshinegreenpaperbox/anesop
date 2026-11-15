package com.anesop.m0.api.platform;

import com.anesop.m0.api.security.AuthPrincipal;

/**
 * 文件业务对象数据范围判定端口（M8-M0-04 评审 B-1）。
 *
 * <p>问题：{@code SCHEDULE}/{@code RECORD}/{@code REPORT} 附件只校验「模块只读权限点」
 * （如 {@code m1:schedule:read}）时，DEPT/SELF 用户可以下载**本科室/本人范围之外**台次或
 * 记录的附件——附件本身携带了业务对象内容，属于越权数据泄露。
 *
 * <p>因此 M0 不直接依赖业务模块（模块边界：m0 是底座，m1/m2/m4 依赖 m0），而是在 m0-api
 * 定义本端口，由装配层（api-bootstrap）适配到 m1 {@code SchedulingService.get} /
 * m2 {@code AnesthesiaRecordService.get} 的既有 DataScope 查询：
 * <ul>
 *   <li>业务对象存在且在调用方数据范围内 → {@code true}；</li>
 *   <li>对象不存在、已删除、超出数据范围（DEPT/SELF/ROOM/SITE）→ {@code false}；</li>
 *   <li>查询异常（含实现未接入/依赖不可用）→ <b>fail-closed</b> 返回 {@code false}，
 *       绝不因适配器故障放行。</li>
 * </ul>
 *
 * <p>{@link FileService} 对 {@code false} 统一返回 404（与业务对象查询语义一致），
 * 不区分「不存在」与「不可见」，避免通过附件存在性探测其他科室业务对象。
 */
public interface FileBusinessVisibilityPort {

    /**
     * 判断调用方是否可见指定业务对象。
     *
     * @param caller       调用方（不得为 null）
     * @param businessType 文件业务类型（{@code SCHEDULE}/{@code RECORD}/{@code REPORT}；
     *                     其他类型由 {@link FileService} 自行处理，不应调用本端口）
     * @param businessId   业务对象标识（已由 {@link FileService} 做标识符模式校验）
     * @return true 表示可见；false 表示不可见或无法判定（fail-closed）
     */
    boolean isVisible(AuthPrincipal caller, String businessType, String businessId);
}
