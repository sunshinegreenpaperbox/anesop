package com.anesop.gateway.buffer;

import com.anesop.gateway.model.VitalSample;

/**
 * 缓冲中的一条待上传记录：样本 + 其所在段与段内结束偏移。
 *
 * @param segment   段文件名
 * @param endOffset 该行结束（含换行）在段内的字节偏移，用于确认位点
 * @param sample    体征样本
 */
public record BufferedRecord(String segment, long endOffset, VitalSample sample) {
}
