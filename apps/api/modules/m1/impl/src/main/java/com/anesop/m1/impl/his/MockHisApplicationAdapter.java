package com.anesop.m1.impl.his;

import com.anesop.m1.api.his.HisSurgeryApplicationBatch;
import com.anesop.m1.api.his.HisSurgeryApplicationPort;
import com.anesop.m1.api.his.HisSurgeryApplicationRecord;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模拟 HIS 手术申请适配器（EXT-01 未就绪期间的端口实现）。
 *
 * <p>只生成 SYNTHETIC 合成数据：固定/可复现的申请号与患者号（同一 seed 结果一致，
 * 保证重复导入按幂等键跳过），批次固定标记 {@code simulated=true}、{@code adapter=MOCK_HIS}。
 * 生成条数严格 1–5：请求 N 条即返回 N 条（由服务层先做 1–5 校验，越界返回 400）。
 *
 * <p>科室/院区标识通过配置注入（{@code anesop.m1.his.mock.department-id} /
 * {@code site-id}），用于数据范围演示；未配置时申请仅带科室名称，DEPT 范围用户不可见
 * （fail-closed，不越权展示）。
 */
@Component
public final class MockHisApplicationAdapter implements HisSurgeryApplicationPort {

    /** 演示姓名（合成，禁止替换为真实患者姓名）。 */
    private static final String[] PATIENT_NAMES = {
        "演示患者甲", "演示患者乙", "演示患者丙", "演示患者丁", "演示患者戊"
    };

    private static final String[] SURGERY_NAMES = {
        "演示术式（全膝关节置换术）",
        "演示术式（腹腔镜胆囊切除术）",
        "演示术式（腰椎间盘髓核摘除术）",
        "演示术式（甲状腺次全切除术）",
        "演示术式（剖宫产术）"
    };

    private static final String[] DIAGNOSES = {
        "演示诊断（膝关节退行性病变）",
        "演示诊断（胆囊结石伴慢性胆囊炎）",
        "演示诊断（腰椎间盘突出症）",
        "演示诊断（甲状腺结节）",
        "演示诊断（妊娠状态）"
    };

    private static final String[] URGENCIES = {"ELECTIVE", "URGENT", "EMERGENCY"};

    private final long baseSeed;
    private final String departmentName;
    private final UUID departmentId;
    private final UUID siteId;

    public MockHisApplicationAdapter(
            @Value("${anesop.m1.his.mock.base-seed:20260101}") long baseSeed,
            @Value("${anesop.m1.his.mock.department-name:演示外科}") String departmentName,
            @Value("${anesop.m1.his.mock.department-id:}") String departmentId,
            @Value("${anesop.m1.his.mock.site-id:}") String siteId) {
        this.baseSeed = baseSeed;
        this.departmentName = departmentName;
        this.departmentId = parseUuid(departmentId);
        this.siteId = parseUuid(siteId);
    }

    @Override
    public HisSurgeryApplicationBatch pull(int count, Long seed) {
        if (count < 1 || count > 5) {
            throw new IllegalArgumentException("模拟 HIS 拉取条数必须为 1–5");
        }
        int effectiveCount = count;
        long effectiveSeed = seed == null ? baseSeed : seed;
        Random random = new Random(effectiveSeed);
        int offset = Math.floorMod((int) (effectiveSeed % 9000), 9000) + 1;
        List<HisSurgeryApplicationRecord> records = new ArrayList<>(effectiveCount);
        for (int index = 0; index < effectiveCount; index++) {
            int serial = offset + index;
            String suffix = String.format("%04d", serial);
            String gender = switch (index % 3) {
                case 0 -> "FEMALE";
                case 1 -> "MALE";
                default -> "UNKNOWN";
            };
            LocalDate birthDate = LocalDate.of(1955 + (index * 4), 1 + (index % 12), 5 + index);
            int age = Period.between(birthDate, LocalDate.of(2026, 11, 1)).getYears();
            records.add(new HisSurgeryApplicationRecord(
                    "SYNTH-HIS-APP-" + suffix,
                    "SYNTH-P-" + suffix,
                    PATIENT_NAMES[index % PATIENT_NAMES.length],
                    gender,
                    birthDate,
                    age,
                    "SYNTH-ZY-" + suffix,
                    departmentName,
                    departmentId,
                    siteId,
                    "演示病区",
                    String.valueOf(10 + index),
                    SURGERY_NAMES[index % SURGERY_NAMES.length],
                    "SYNTH-OP-" + suffix,
                    URGENCIES[(index + random.nextInt(3)) % URGENCIES.length],
                    60 + (index * 30),
                    "演示术者" + (char) ('A' + index),
                    DIAGNOSES[index % DIAGNOSES.length],
                    "模拟 HIS 申请（SYNTHETIC 合成数据）"));
        }
        return new HisSurgeryApplicationBatch(true, "MOCK_HIS", records);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("anesop.m1.his.mock.*-id 必须是合法 UUID", e);
        }
    }
}
