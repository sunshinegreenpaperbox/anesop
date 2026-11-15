package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.platform.model.FileMeta;
import com.anesop.contracts.platform.model.FilePage;
import com.anesop.contracts.platform.model.FileUploadRequest;
import com.anesop.m0.api.platform.FileDownload;
import com.anesop.m0.api.platform.FileService;
import com.anesop.m0.api.security.AuthPermissions;
import com.anesop.m0.api.security.AuthRequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件管理与下载接口（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>授权：上传 {@code m0:file:write}；下载/元数据/我的文件 {@code m0:file:read} + 可见性
 * （上传者 / SYS_ADMIN / 业务模块只读权限 + 业务对象数据范围）。**不得存 PHI**：类型白名单
 * （PDF/CSV/TXT/PNG/JPEG）与 20 MiB 上限由服务层与库层双重校验；病毒扫描为占位 EXT-07。
 * **不持久化客户端原始文件名**：响应 {@code fileName} 为服务端生成的 {@code file-<id>.<ext>}。
 *
 * <p>下载响应固定 {@code X-Content-Type-Options: nosniff}，`Content-Disposition` 使用
 * RFC 5987 {@code filename*=UTF-8''} 编码（文件名已在服务层过滤 CR/LF/引号/路径分隔符），
 * 并带 {@code X-Content-Sha256} 供下载方校验完整性。
 */
@RestController
@RequestMapping("/v1/files")
@Tag(name = "文件管理", description = "非 PHI 文件上传（类型/大小白名单）、元数据查询与鉴权下载")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_FILE_READ + "')")
    @Operation(operationId = "listMyFiles", summary = "查询我上传的文件（分页，仅本人）")
    public FilePage listMine(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return fileService.listMine(AuthPrincipals.from(jwt), page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_FILE_WRITE + "')")
    @Operation(operationId = "uploadFile", summary = "上传文件（非 PHI，JSON + Base64）")
    public FileMeta upload(@Valid @RequestBody FileUploadRequest request,
            @AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        return fileService.upload(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/{fileId}")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_FILE_READ + "')")
    @Operation(operationId = "downloadFile", summary = "下载文件（鉴权 + 内容寻址读取）")
    public ResponseEntity<byte[]> download(@PathVariable("fileId") UUID fileId,
            @AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        FileDownload download = fileService.download(fileId, AuthPrincipals.from(jwt),
                AuthPrincipals.permissions(jwt), AuthPrincipals.roles(jwt), context);
        FileMeta meta = download.meta();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(meta.getContentType().getValue()));
        headers.setContentLength(download.size());
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(meta.getFileName()));
        headers.set("X-Content-Sha256", meta.getSha256());
        headers.set("X-Content-Type-Options", "nosniff");
        return new ResponseEntity<>(download.content(), headers, HttpStatus.OK);
    }

    @GetMapping("/{fileId}/meta")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_FILE_READ + "')")
    @Operation(operationId = "getFileMeta", summary = "查询文件元数据（不含内容）")
    public FileMeta meta(@PathVariable("fileId") UUID fileId,
            @AuthenticationPrincipal Jwt jwt) {
        return fileService.meta(fileId, AuthPrincipals.from(jwt),
                AuthPrincipals.permissions(jwt), AuthPrincipals.roles(jwt));
    }

    /** RFC 5987 附件文件名；文件名已由服务层过滤控制字符与路径分隔符。 */
    private static String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }
}
