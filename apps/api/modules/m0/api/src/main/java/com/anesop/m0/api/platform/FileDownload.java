package com.anesop.m0.api.platform;

import com.anesop.contracts.platform.model.FileMeta;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;

/**
 * 下载结果（元数据 + 内容）。
 *
 * <p>byte[] 做防御性拷贝：构造与访问都复制，避免调用方修改内部缓冲
 * （SpotBugs EI_EXPOSE_REP/EI_EXPOSE_REP2），也避免文件内容被跨请求共享。
 */
public final class FileDownload {

    private final FileMeta meta;
    private final byte[] content;

    public FileDownload(FileMeta meta, byte[] content) {
        this.meta = Objects.requireNonNull(meta, "meta");
        this.content = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
    }

    /**
     * 文件元数据（生成的契约 DTO 带 setter，SpotBugs 视为可变对象）。
     *
     * <p>该 DTO 只在一次请求内构造并直接写响应，服务内部不保留、不修改它；返回引用不会造成
     * 跨请求可变状态泄漏，故抑制 EI_EXPOSE_REP 误报。
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP",
            justification = "契约 DTO 为单次请求内构造并写响应的只读载体，服务不保留/修改该引用")
    public FileMeta meta() {
        return meta;
    }

    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }

    public int size() {
        return content.length;
    }
}
