package com.anesop.m2.impl.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 体征查询行（{@code m2_anes.anesthesia_vital} 读取；服务层展开为指标行）。
 */
public class VitalRow {

    private Long seq;
    private Instant measuredAt;
    private Integer hr;
    private Integer spo2;
    private Integer nibpSys;
    private Integer nibpDia;
    private BigDecimal etco2;
    private BigDecimal temp;
    private Integer quality;
    private String qualityFlags;

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(Instant measuredAt) {
        this.measuredAt = measuredAt;
    }

    public Integer getHr() {
        return hr;
    }

    public void setHr(Integer hr) {
        this.hr = hr;
    }

    public Integer getSpo2() {
        return spo2;
    }

    public void setSpo2(Integer spo2) {
        this.spo2 = spo2;
    }

    public Integer getNibpSys() {
        return nibpSys;
    }

    public void setNibpSys(Integer nibpSys) {
        this.nibpSys = nibpSys;
    }

    public Integer getNibpDia() {
        return nibpDia;
    }

    public void setNibpDia(Integer nibpDia) {
        this.nibpDia = nibpDia;
    }

    public BigDecimal getEtco2() {
        return etco2;
    }

    public void setEtco2(BigDecimal etco2) {
        this.etco2 = etco2;
    }

    public BigDecimal getTemp() {
        return temp;
    }

    public void setTemp(BigDecimal temp) {
        this.temp = temp;
    }

    public Integer getQuality() {
        return quality;
    }

    public void setQuality(Integer quality) {
        this.quality = quality;
    }

    public String getQualityFlags() {
        return qualityFlags;
    }

    public void setQualityFlags(String qualityFlags) {
        this.qualityFlags = qualityFlags;
    }
}
