package com.anesop.m2.impl.pacu;

/**
 * Aldrete 评分计算器（M3-M10-01，临床规则，需人工逐行）。
 *
 * <p>五项各 0–2 分，总分 0–10（五项之和），分项定义按 Aldrete 1995 修订版原文校准：
 * <ul>
 *   <li>activity 活动能力：0 不能活动肢体（自主或遵嘱）/ 1 可活动两个肢体（自主或遵嘱）/
 *       2 可活动四个肢体（自主或遵嘱）；</li>
 *   <li>respiration 呼吸：0 呼吸暂停 / 1 呼吸困难或呼吸受限 / 2 能深呼吸并有效咳嗽；</li>
 *   <li>circulation 循环：0 血压偏离术前 &gt;50% / 1 偏离 20%–50% / 2 偏离 &lt;20%；</li>
 *   <li>consciousness 意识：0 无反应 / 1 呼之可醒 / 2 完全清醒；</li>
 *   <li>oxygenation 氧合：0 吸氧下 SpO2 &lt;90% / 1 需吸氧维持 SpO2 &gt;90% /
 *       2 空气下 SpO2 &gt;92%。</li>
 * </ul>
 *
 * <p>来源：Aldrete JA, Kroulik D. A postanesthetic recovery score. Anesth Analg.
 * 1970;49(6):924-934；修订版 Aldrete JA. The post-anesthesia recovery score revisited.
 * J Clin Monit. 1995;11(1):41-46.
 *
 * <p><b>转出阈值不在本类硬编码</b>：由配置 {@code anesop.m2.pacu.aldrete-threshold}
 * （默认 9）提供，院方阈值【待确认】。本类只负责分项合法性与总分计算，不做临床决策，
 * 也不替代医师判断（AGENTS H-06）。
 */
public final class AldreteCalculator {

    /** 单项最小分。 */
    public static final int ITEM_MIN = 0;

    /** 单项最大分。 */
    public static final int ITEM_MAX = 2;

    /** 总分上限（五项各 2 分）。 */
    public static final int TOTAL_MAX = ITEM_MAX * 5;

    private AldreteCalculator() {
    }

    /**
     * 计算总分：五项之和（0–10）。
     *
     * @throws IllegalArgumentException 任一项越界（服务层应先做字段级 400 校验）
     */
    public static int total(int activity, int respiration, int circulation, int consciousness,
            int oxygenation) {
        return requireItem(activity, "activity")
                + requireItem(respiration, "respiration")
                + requireItem(circulation, "circulation")
                + requireItem(consciousness, "consciousness")
                + requireItem(oxygenation, "oxygenation");
    }

    /** 单项是否在合法区间（0–2，null 视为缺项/非法）。 */
    public static boolean isItemValid(Integer value) {
        return value != null && value >= ITEM_MIN && value <= ITEM_MAX;
    }

    /** 总分是否达到转出阈值（达到即满足，含等于）。 */
    public static boolean meetsThreshold(int total, int threshold) {
        return total >= threshold;
    }

    private static int requireItem(int value, String field) {
        if (value < ITEM_MIN || value > ITEM_MAX) {
            throw new IllegalArgumentException("Aldrete 分项越界：" + field + "=" + value);
        }
        return value;
    }
}
