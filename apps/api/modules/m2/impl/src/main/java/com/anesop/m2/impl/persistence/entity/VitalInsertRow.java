package com.anesop.m2.impl.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 体征入库行（{@code m2_anes.anesthesia_vital} 批量插入参数）。
 *
 * <p>字段与 V4 列一一对应；{@code measuredAt} 永远保留设备原值（行级幂等键），
 * 时钟校正结果只写 {@code measuredAtCorrected}。
 */
public class VitalInsertRow {

    private String gatewayId;
    private String deviceId;
    private Long seq;
    private Instant measuredAt;
    private Instant measuredAtCorrected;
    private Instant receivedAt;
    private Integer hr;
    private Integer spo2;
    private Integer nibpSys;
    private Integer nibpDia;
    private BigDecimal etco2;
    private BigDecimal temp;
    private Integer quality;
    private String qualityFlags;
    private String source;
    private Boolean synthetic;

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

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

    public Instant getMeasuredAtCorrected() {
        return measuredAtCorrected;
    }

    public void setMeasuredAtCorrected(Instant measuredAtCorrected) {
        this.measuredAtCorrected = measuredAtCorrected;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Boolean getSynthetic() {
        return synthetic;
    }

    public void setSynthetic(Boolean synthetic) {
        this.synthetic = synthetic;
    }
}
