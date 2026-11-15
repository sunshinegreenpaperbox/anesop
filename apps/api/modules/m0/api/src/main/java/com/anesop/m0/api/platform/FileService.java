package com.anesop.m0.api.platform;

import com.anesop.contracts.platform.model.FileMeta;
import com.anesop.contracts.platform.model.FilePage;
import com.anesop.contracts.platform.model.FileUploadRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.util.Set;
import java.util.UUID;

/**
 * 文件服务（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>硬规则：
 * <ul>
 *   <li><b>不得存 PHI</b>：内容类型白名单 {@code application/pdf}/{@code text/csv}/
 *       {@code text/plain}/{@code image/png}/{@code image/jpeg}，大小 ≤20 MiB；禁止临床影像
 *       （DICOM/视频）、身份证明与含患者标识的表格/截图；病毒扫描为占位（EXT-07）；</li>
 *   <li><b>不持久化客户端原始文件名</b>：{@code sys_file} 无文件名列，展示名由服务端按
 *       {@code file-<id>.<ext>} 生成；审计 detail 只含业务类型/大小/摘要前 12 位，
 *       <b>不含 fileName 与 businessId</b>（评审 B-2）；</li>
 *   <li><b>内容寻址</b>：服务端计算 SHA-256，按内容寻址经 {@link FileStorage} 落盘，
 *       同内容只存一份；每次上传独立元数据（便于按上传者控制可见性）；</li>
 *   <li><b>下载鉴权</b>：上传者本人 / {@code SYS_ADMIN} / 业务关联所需模块只读权限，
 *       且业务对象必须落在调用方数据范围内（{@link FileBusinessVisibilityPort}）；
 *       {@code TEMPLATE} 对持 {@code m0:file:read} 者公开，{@code ATTACHMENT} 仅上传者与
 *       {@code SYS_ADMIN}；缺模块权限返回 403，业务对象超出数据范围/不存在返回 404；</li>
 *   <li><b>我的文件</b>：{@link #listMine} 只返回 {@code uploader_id = 当前用户} 且未删除的
 *       元数据，不做跨用户枚举。</li>
 * </ul>
 */
public interface FileService {

    /**
     * 上传文件（JSON + Base64）。
     *
     * @throws M0ValidationException      字段级非法（Base64/文件名/businessId 等）→ 400
     * @throws M0UnprocessableException   类型/大小被业务规则拒绝 → 422
     */
    FileMeta upload(FileUploadRequest request, AuthPrincipal caller, AuthRequestContext context);

    /**
     * 下载文件内容（含元数据）。
     *
     * @throws java.util.NoSuchElementException 文件不存在、已删除或业务对象超出数据范围 → 404
     * @throws org.springframework.security.access.AccessDeniedException 缺模块只读权限 → 403
     */
    FileDownload download(UUID fileId, AuthPrincipal caller, Set<String> permissions,
            Set<String> roles, AuthRequestContext context);

    /**
     * 查询文件元数据（不含内容与存储路径）。
     *
     * @throws java.util.NoSuchElementException 文件不存在、已删除或业务对象超出数据范围 → 404
     * @throws org.springframework.security.access.AccessDeniedException 缺模块只读权限 → 403
     */
    FileMeta meta(UUID fileId, AuthPrincipal caller, Set<String> permissions, Set<String> roles);

    /**
     * 查询当前用户上传的文件（创建时间倒序，分页夹紧）。
     *
     * <p>只按 {@code uploader_id = 当前用户} 过滤，不返回他人文件；空数据返回空页。
     */
    FilePage listMine(AuthPrincipal caller, int page, int size);
}
