package com.anesop.m0.impl.crypto;

/**
 * 字段加解密失败（密文被篡改、密钥版本不匹配或格式非法）。
 *
 * <p>异常信息固定为安全文案，绝不包含密文原文、明文、密钥或 IV：这些信息一旦进入日志、
 * 审计或错误响应即等同泄露（AGENTS 患者数据与安全红线）。调用方按“字段不可用”处理。
 */
public class PhoneCryptoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PhoneCryptoException(String message) {
        super(message);
    }

    public PhoneCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
