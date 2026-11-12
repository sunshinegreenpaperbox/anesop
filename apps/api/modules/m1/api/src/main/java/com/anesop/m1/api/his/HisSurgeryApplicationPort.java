package com.anesop.m1.api.his;

/**
 * HIS 手术申请拉取端口（端口—适配器，方案书 §3.5.3 I-01a）。
 *
 * <p>生产适配器（HL7 v2 / REST，EXT-01）未就绪；当前实现为
 * {@code MockHisApplicationAdapter}，只生成 SYNTHETIC 演示数据，并在批次中标记
 * {@code simulated=true}/{@code adapter=MOCK_HIS}。实现不得记录患者标识。
 */
public interface HisSurgeryApplicationPort {

    /**
     * 拉取一批手术申请。
     *
     * @param count 期望条数（1–5；实现负责钳制到 3–5 的演示范围）
     * @param seed  随机种子；相同种子必须生成相同申请号，保证重复导入幂等
     * @return 模拟批次（含 simulated 标记）
     */
    HisSurgeryApplicationBatch pull(int count, Long seed);
}
