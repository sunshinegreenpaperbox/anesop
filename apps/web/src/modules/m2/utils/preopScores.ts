import type { PreopDasiActivity, PreopScores } from '../api/m2'

/**
 * 术前评分前端预览（**仅展示，最终以服务端计算结果为准**）。
 *
 * 与服务端 `PreopScoreCalculator` 使用同一套公开量表口径：
 * - RCRI（Lee 1999）6 项各 1 分；0 低 / 1 中 / ≥2 高；
 * - Apfel（1999）4 项；0–1 低 / 2 中 / ≥3 高；
 * - STOP-BANG（Chung 2008）8 项；0–2 低 / 3–4 中 / ≥5 高；
 * - DASI（Hlatky 1989）12 项权重合计 0–58.2；功能容量 <4 METs 的切点【待确认】。
 * 综合风险取三者最高级（保守取高），属展示约定【待确认】，不替代医师判断。
 * 修改本文件必须同步服务端实现与 docs/modules/m2/README.md。
 */

export type RiskLevel = PreopScores['riskLevel']

export interface PreopScoreDraft {
  rcri: Record<RcriKey, boolean>
  apfel: Record<ApfelKey, boolean>
  stopBang: Record<StopBangKey, boolean>
  dasiActivities: PreopDasiActivity[]
}

export const RCRI_ITEMS = [
  { key: 'highRiskSurgery', label: '高危手术（腹腔内/胸腔内/腹股沟以上血管）' },
  { key: 'ischemicHeartDisease', label: '缺血性心脏病史' },
  { key: 'congestiveHeartFailure', label: '充血性心力衰竭史' },
  { key: 'cerebrovascularDisease', label: '脑血管病史' },
  { key: 'insulinDependentDiabetes', label: '胰岛素依赖型糖尿病' },
  { key: 'renalImpairment', label: '肾功能不全（肌酐 > 2.0 mg/dL）' },
] as const
export type RcriKey = (typeof RCRI_ITEMS)[number]['key']

export const APFEL_ITEMS = [
  { key: 'female', label: '女性' },
  { key: 'ponvOrMotionSicknessHistory', label: '既往 PONV 或晕动症史' },
  { key: 'nonSmoker', label: '不吸烟' },
  { key: 'postoperativeOpioid', label: '术后使用阿片类药物' },
] as const
export type ApfelKey = (typeof APFEL_ITEMS)[number]['key']

export const STOP_BANG_ITEMS = [
  { key: 'snoring', label: '打鼾' },
  { key: 'tiredness', label: '白天嗜睡/疲乏' },
  { key: 'observedApnea', label: '目击呼吸暂停' },
  { key: 'hypertension', label: '高血压' },
  { key: 'bmiOver35', label: 'BMI > 35 kg/m²' },
  { key: 'ageOver50', label: '年龄 > 50 岁' },
  { key: 'neckCircumferenceOver40', label: '颈围 > 40 cm' },
  { key: 'male', label: '男性' },
] as const
export type StopBangKey = (typeof STOP_BANG_ITEMS)[number]['key']

/** DASI 12 项及权重（Hlatky 1989），合计 58.2。 */
export const DASI_ITEMS: readonly {
  code: PreopDasiActivity
  label: string
  weight: number
}[] = [
  { code: 'SELF_CARE', label: '自理（进食、穿衣、洗澡、如厕）', weight: 2.75 },
  { code: 'WALK_INDOORS', label: '室内行走', weight: 1.75 },
  { code: 'WALK_BLOCK', label: '平地步行 1–2 街区', weight: 2.75 },
  { code: 'CLIMB_STAIRS', label: '上一层楼或爬坡', weight: 5.5 },
  { code: 'RUN_SHORT_DISTANCE', label: '短距离跑', weight: 8 },
  { code: 'LIGHT_HOUSEHOLD_WORK', label: '轻家务（除尘、洗碗）', weight: 2.7 },
  { code: 'MODERATE_HOUSEHOLD_WORK', label: '中等家务（吸尘、扫地、提杂货）', weight: 3.5 },
  { code: 'HEAVY_HOUSEHOLD_WORK', label: '重家务（擦地、搬重物）', weight: 8 },
  { code: 'YARD_WORK', label: '庭院劳动（耙叶、除草、推割草机）', weight: 4.5 },
  { code: 'SEXUAL_RELATIONS', label: '性生活', weight: 5.25 },
  { code: 'MODERATE_RECREATION', label: '中等娱乐（高尔夫、保龄球、双打网球）', weight: 6 },
  { code: 'STRENUOUS_SPORTS', label: '剧烈运动（游泳、单打网球、滑雪）', weight: 7.5 },
]

export interface PreopScorePreview {
  rcriScore: number
  rcriRisk: RiskLevel
  apfelScore: number
  apfelRisk: RiskLevel
  stopBangScore: number
  stopBangRisk: RiskLevel
  dasiScore: number
  functionalCapacityLow: boolean
  riskLevel: RiskLevel
}

function countTrue(values: readonly boolean[]): number {
  return values.filter(Boolean).length
}

function riskOf(score: number, lowMax: number, moderateMax: number): RiskLevel {
  if (score <= lowMax) return 'LOW'
  return score <= moderateMax ? 'MODERATE' : 'HIGH'
}

function maxRisk(...risks: RiskLevel[]): RiskLevel {
  if (risks.includes('HIGH')) return 'HIGH'
  return risks.includes('MODERATE') ? 'MODERATE' : 'LOW'
}

export function previewScores(draft: PreopScoreDraft): PreopScorePreview {
  const rcriScore = countTrue(RCRI_ITEMS.map((item) => draft.rcri[item.key]))
  const apfelScore = countTrue(APFEL_ITEMS.map((item) => draft.apfel[item.key]))
  const stopBangScore = countTrue(STOP_BANG_ITEMS.map((item) => draft.stopBang[item.key]))
  const dasiRaw = DASI_ITEMS.filter((item) => draft.dasiActivities.includes(item.code)).reduce(
    (total, item) => total + item.weight,
    0,
  )
  const dasiScore = Math.round(dasiRaw * 100) / 100
  // VO2peak(mL/kg/min) = 0.43 × DASI + 9.6；< 14（≈4 METs）视为功能容量偏低【待确认】。
  const estimatedVo2 = 0.43 * dasiScore + 9.6

  const rcriRisk = riskOf(rcriScore, 0, 1)
  const apfelRisk = riskOf(apfelScore, 1, 2)
  const stopBangRisk = riskOf(stopBangScore, 2, 4)
  return {
    rcriScore,
    rcriRisk,
    apfelScore,
    apfelRisk,
    stopBangScore,
    stopBangRisk,
    dasiScore,
    functionalCapacityLow: estimatedVo2 < 14,
    riskLevel: maxRisk(rcriRisk, apfelRisk, stopBangRisk),
  }
}
