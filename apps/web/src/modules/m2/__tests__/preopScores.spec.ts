import { describe, expect, it } from 'vitest'
import { DASI_ITEMS, previewScores, type PreopScoreDraft } from '../utils/preopScores'
import type { PreopDasiActivity } from '../api/m2'

function draft(overrides: Partial<PreopScoreDraft> = {}): PreopScoreDraft {
  return {
    rcri: {
      highRiskSurgery: false,
      ischemicHeartDisease: false,
      congestiveHeartFailure: false,
      cerebrovascularDisease: false,
      insulinDependentDiabetes: false,
      renalImpairment: false,
    },
    apfel: {
      female: false,
      ponvOrMotionSicknessHistory: false,
      nonSmoker: false,
      postoperativeOpioid: false,
    },
    stopBang: {
      snoring: false,
      tiredness: false,
      observedApnea: false,
      hypertension: false,
      bmiOver35: false,
      ageOver50: false,
      neckCircumferenceOver40: false,
      male: false,
    },
    dasiActivities: [],
    ...overrides,
  }
}

describe('preopScores 预览（与服务端同口径）', () => {
  it('全部阴性：0 分低危、DASI 0 且功能容量偏低', () => {
    const preview = previewScores(draft())
    expect(preview.rcriScore).toBe(0)
    expect(preview.apfelScore).toBe(0)
    expect(preview.stopBangScore).toBe(0)
    expect(preview.dasiScore).toBe(0)
    expect(preview.functionalCapacityLow).toBe(true)
    expect(preview.riskLevel).toBe('LOW')
  })

  it('中危边界：RCRI 1 / Apfel 2 / STOP-BANG 3 均为 MODERATE', () => {
    const preview = previewScores(
      draft({
        rcri: { ...draft().rcri, highRiskSurgery: true },
        apfel: {
          female: true,
          ponvOrMotionSicknessHistory: true,
          nonSmoker: false,
          postoperativeOpioid: false,
        },
        stopBang: {
          snoring: true,
          tiredness: true,
          observedApnea: true,
          hypertension: false,
          bmiOver35: false,
          ageOver50: false,
          neckCircumferenceOver40: false,
          male: false,
        },
      }),
    )
    expect(preview.rcriRisk).toBe('MODERATE')
    expect(preview.apfelRisk).toBe('MODERATE')
    expect(preview.stopBangRisk).toBe('MODERATE')
    expect(preview.riskLevel).toBe('MODERATE')
  })

  it('高危：任一量表达高危即综合高危，DASI 12 项合计 58.2', () => {
    const allDasi = DASI_ITEMS.map((item) => item.code) as PreopDasiActivity[]
    const preview = previewScores(
      draft({
        rcri: {
          ...draft().rcri,
          highRiskSurgery: true,
          ischemicHeartDisease: true,
        },
        dasiActivities: allDasi,
      }),
    )
    expect(preview.rcriScore).toBe(2)
    expect(preview.rcriRisk).toBe('HIGH')
    expect(preview.dasiScore).toBe(58.2)
    expect(preview.functionalCapacityLow).toBe(false)
    expect(preview.riskLevel).toBe('HIGH')
  })
})
