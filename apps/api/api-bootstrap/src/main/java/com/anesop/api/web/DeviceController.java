package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.acquisition.AcquisitionService;
import com.anesop.m2.api.acquisition.DeviceHealth;
import com.anesop.m2.api.acquisition.DeviceList;
import com.anesop.m2.api.acquisition.DeviceRegisterRequest;
import com.anesop.m2.api.acquisition.DeviceView;
import com.anesop.m2.api.acquisition.IngestRequest;
import com.anesop.m2.api.acquisition.IngestResult;
import com.anesop.m2.api.acquisition.RegisterOutcome;
import com.anesop.m2.api.acquisition.VitalPage;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.security.M2Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备采集接口（M3-M8-01，契约 device.yaml 0.1.0）。
 *
 * <p>授权：设备台账/体征查询/健康摘要需要 {@code m2:device:read}，登记与换台绑定需要
 * {@code m2:device:write}（V14 授予 SYS_ADMIN/SYS_OR_MANAGER）。网关上报不使用用户 JWT，
 * 由内部请求头 {@code X-Gateway-Token} 认证（服务层 fail-closed 校验，令牌不落日志）；
 * 该路径在安全配置中 permitAll，未配置密钥时一律 401。
 *
 * <p>错误语义：参数/格式 400、未认证或网关令牌无效 401、缺少权限 403、设备未登记 404、
 * 并发登记/换台冲突 409、窗口超限等业务规则 422。
 */
@RestController
@RequestMapping("/v1/devices")
@Tag(name = "设备采集", description = "设备登记与术间绑定、网关批量上报（幂等）、体征窗口查询与采集健康摘要")
public class DeviceController {

    private static final String HEADER_GATEWAY_TOKEN = "X-Gateway-Token";

    private final AcquisitionService acquisitionService;

    public DeviceController(AcquisitionService acquisitionService) {
        this.acquisitionService = acquisitionService;
    }

    /** 查询设备登记列表（含推导状态与质量统计）。 */
    @GetMapping
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_DEVICE_READ + "')")
    @Operation(operationId = "listDevices", summary = "查询设备登记列表")
    public DeviceList listDevices(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "gatewayId", required = false) String gatewayId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "roomId", required = false) String roomId,
            AuthRequestContext context) {
        return acquisitionService.listDevices(gatewayId, status, parseUuid(roomId), AuthPrincipals.from(jwt),
                context);
    }

    /** 登记设备并绑定术间；首次登记 201，幂等/换台 200。 */
    @PostMapping
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_DEVICE_WRITE + "')")
    @Operation(operationId = "registerDevice", summary = "登记设备并绑定术间")
    public ResponseEntity<DeviceView> registerDevice(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DeviceRegisterRequest request, AuthRequestContext context) {
        RegisterOutcome outcome = acquisitionService.register(request, AuthPrincipals.from(jwt), context);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.device());
    }

    /** 采集健康摘要（运维）。 */
    @GetMapping("/health")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_DEVICE_READ + "')")
    @Operation(operationId = "getDeviceHealth", summary = "采集健康摘要（运维）")
    public DeviceHealth getDeviceHealth() {
        return acquisitionService.health();
    }

    /** 按窗口查询标准化体征（按采样帧分页）。 */
    @GetMapping("/{gatewayId}/{deviceId}/vitals")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_DEVICE_READ + "')")
    @Operation(operationId = "listDeviceVitals", summary = "按窗口查询标准化体征")
    public VitalPage listDeviceVitals(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("gatewayId") String gatewayId,
            @PathVariable("deviceId") String deviceId,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "quality", required = false) Integer quality,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            AuthRequestContext context) {
        return acquisitionService.queryVitals(gatewayId, deviceId, parseInstant(from, "from"),
                parseInstant(to, "to"), quality, page, size, AuthPrincipals.from(jwt), context);
    }

    /**
     * 网关批量上报（内部令牌）。
     *
     * <p>路径在 {@code SecurityConfiguration} 中 permitAll；令牌校验在服务层完成（fail-closed），
     * 因此不依赖用户 JWT，也不会把令牌写入日志或审计。
     */
    @PostMapping("/{gatewayId}/{deviceId}/ingest")
    @Operation(operationId = "ingestDeviceVitals", summary = "网关批量上报标准化体征（内部令牌）")
    public ResponseEntity<IngestResult> ingestDeviceVitals(
            @PathVariable("gatewayId") String gatewayId,
            @PathVariable("deviceId") String deviceId,
            @RequestHeader(name = HEADER_GATEWAY_TOKEN, required = false) String gatewayToken,
            @Valid @RequestBody IngestRequest request,
            AuthRequestContext context) {
        IngestResult result = acquisitionService.ingest(gatewayId, deviceId, gatewayToken, request, context);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new M2ValidationException("roomId", "术间标识格式不正确");
        }
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new M2ValidationException(field, "时间不能为空");
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException invalidOffset) {
            try {
                return Instant.parse(value.trim());
            } catch (DateTimeParseException invalidInstant) {
                throw new M2ValidationException(field, "时间必须是 RFC 3339 格式");
            }
        }
    }
}
