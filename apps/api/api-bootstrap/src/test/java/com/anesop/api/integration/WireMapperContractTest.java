package com.anesop.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.contracts.drug.model.PharmacyAdapterStatus;
import com.anesop.contracts.material.model.SpdAdapterStatus;
import com.anesop.contracts.material.model.SpdSyncResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 字段映射的 fail-closed 守卫（M5-M19-01）。锁定“外部字段缺失/非法值必须报错，不得静默空值”：
 * 真实接口接入时字段改名/值域变化应在此先失败，而不是产生脏数据。
 */
class WireMapperContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void hisSexAndUrgencyValueDomainsMapAndRejectUnknownCodes() {
        assertThat(WireMapper.mapSex("F")).isEqualTo("FEMALE");
        assertThat(WireMapper.mapSex("M")).isEqualTo("MALE");
        assertThat(WireMapper.mapSex("U")).isEqualTo("UNKNOWN");
        assertThat(WireMapper.mapUrgency("择期")).isEqualTo("ELECTIVE");
        assertThat(WireMapper.mapUrgency("限期")).isEqualTo("URGENT");
        assertThat(WireMapper.mapUrgency("急诊")).isEqualTo("EMERGENCY");

        assertThatThrownBy(() -> WireMapper.mapSex("X")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 HIS 性别代码");
        assertThatThrownBy(() -> WireMapper.mapUrgency("加急")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 HIS 紧急度");
    }

    @Test
    void missingOrIllegalExternalFieldsFailClosed() throws Exception {
        JsonNode drug = MAPPER.readTree("""
                {"drugCode":"PMS-D-0001","drugName":"演示药品","commonName":"演示通用名",
                 "spec":"1ml","unit":"支","doseForm":"INJECTION","drugClass":"OPIOID",
                 "narcoticFlag":1,"highRiskFlag":0,"aliasList":["演示别名（合成）"]}
                """);
        assertThat(WireMapper.drugCreateRequest(drug))
                .containsEntry("narcotic", true)
                .containsEntry("highRisk", false)
                .containsEntry("aliases", List.of("演示别名（合成）"));

        JsonNode missingName = MAPPER.readTree("{\"drugCode\":\"PMS-D-0002\"}");
        assertThatThrownBy(() -> WireMapper.drugCreateRequest(missingName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("drugName");

        JsonNode illegalFlag = MAPPER.readTree("""
                {"drugCode":"PMS-D-0003","drugName":"演示药品","commonName":"演示通用名",
                 "spec":"1ml","unit":"支","doseForm":"INJECTION","drugClass":"OPIOID",
                 "narcoticFlag":2,"highRiskFlag":0,"aliasList":[]}
                """);
        assertThatThrownBy(() -> WireMapper.drugCreateRequest(illegalFlag))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0/1");

        JsonNode missingTime = MAPPER.readTree("""
                {"system":"PMS-HOSPITAL","systemName":"药房系统（模拟）","online":true,
                 "pendingOrders":0,"message":"在线"}
                """);
        assertThatThrownBy(() -> WireMapper.pharmacyStatus(missingTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastSyncAt");
    }

    @Test
    void degradedStatusesCarryDegradedSemanticsAndNoStaleSyncTime() {
        PharmacyAdapterStatus pharmacy = WireMapper.pharmacyDegraded("timeout");
        assertThat(pharmacy.getAdapterCode()).isEqualTo("EXT-02");
        assertThat(pharmacy.getMode()).isEqualTo(PharmacyAdapterStatus.ModeEnum.SIMULATED);
        assertThat(pharmacy.getAvailable()).isFalse();
        assertThat(pharmacy.getDegraded()).isTrue();
        assertThat(pharmacy.getLastSyncedAt()).isNull();
        assertThat(pharmacy.getPendingSyncCount()).isEqualTo(1);
        assertThat(pharmacy.getMessage()).contains("降级").contains("timeout");

        SpdAdapterStatus spd = WireMapper.spdDegraded("timeout");
        assertThat(spd.getAdapterCode()).isEqualTo("EXT-03");
        assertThat(spd.getAvailable()).isFalse();
        assertThat(spd.getDegraded()).isTrue();
        assertThat(spd.getLastSyncedAt()).isNull();

        SpdSyncResult sync = WireMapper.spdSyncDegraded("timeout");
        assertThat(sync.getAdapterCode()).isEqualTo("EXT-03");
        assertThat(sync.getSuccess()).isFalse();
        assertThat(sync.getDegraded()).isTrue();
        assertThat(sync.getPulledCount()).isZero();
        assertThat(sync.getPushedCount()).isZero();
        assertThat(sync.getSyncedAt()).isNotNull();
    }

    @Test
    void materialFieldsMapIncludingFlagsAndRemark() throws Exception {
        JsonNode material = MAPPER.readTree("""
                {"materialCode":"SPD-M-0009","materialName":"演示耗材","specification":"常规",
                 "unit":"套","categoryCode":"CATHETER","udiDi":"UDI-DI-SYNTH-0009",
                 "highValueFlag":1,"expiryManagedFlag":0,"remark":"SYNTHETIC"}
                """);
        assertThat(WireMapper.materialCreateRequest(material))
                .containsEntry("code", "SPD-M-0009")
                .containsEntry("highValue", true)
                .containsEntry("expiryManaged", false)
                .containsEntry("udiDi", "UDI-DI-SYNTH-0009");
    }

    @Test
    void contractRequiredFieldListsMatchGeneratedModels() throws Exception {
        JsonNode pharmacy = MAPPER.valueToTree(WireMapper.pharmacyStatus(MAPPER.readTree("""
                {"system":"PMS-HOSPITAL","systemName":"药房系统（模拟）","online":true,
                 "pendingOrders":2,"lastSyncAt":"2026-11-14T08:00:00+08:00","message":"在线"}
                """)));
        WireMapper.PHARMACY_STATUS_CONTRACT_FIELDS.forEach(field ->
                assertThat(pharmacy.has(field)).as("药房状态契约字段: " + field).isTrue());

        JsonNode spdSync = MAPPER.valueToTree(WireMapper.spdSync(MAPPER.readTree("""
                {"syncNo":"SPD-SYNC-0001","acknowledged":true,"pulled":2,"pushed":0,
                 "syncedAt":"2026-11-14T07:00:00+08:00","message":"完成","idempotencyKey":"K"}
                """)));
        WireMapper.SPD_SYNC_CONTRACT_FIELDS.forEach(field ->
                assertThat(spdSync.has(field)).as("SPD 同步契约字段: " + field).isTrue());
    }
}
