package com.anesop.m1.api.his;

import java.util.List;

/**
 * 一次 HIS 拉取的结果批次。
 *
 * @param simulated    是否模拟数据；当前 Mock 适配器固定 true（EXT-01）
 * @param adapter      适配器标识；当前固定 MOCK_HIS
 * @param applications 申请记录（SYNTHETIC）
 */
public record HisSurgeryApplicationBatch(
        boolean simulated,
        String adapter,
        List<HisSurgeryApplicationRecord> applications) {

    public HisSurgeryApplicationBatch {
        applications = applications == null ? List.of() : List.copyOf(applications);
    }
}
