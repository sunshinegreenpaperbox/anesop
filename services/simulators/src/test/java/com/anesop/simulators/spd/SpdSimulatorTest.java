package com.anesop.simulators.spd;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.simulators.FailureMode;
import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.spd.SpdSimulator.SpdMaterial;
import com.anesop.simulators.support.TestHttp;
import com.anesop.simulators.support.TestHttp.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPD 模拟器：耗材目录、同步幂等回执、在线状态与故障注入。 */
class SpdSimulatorTest {

    @Test
    void materialCatalogUsesSpdFieldNamesAndKeywordFilter() throws Exception {
        SpdSimulator simulator = new SpdSimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            Response all = TestHttp.get(simulator.baseUrl() + "/spd/v1/materials");
            assertThat(all.status()).isEqualTo(200);
            assertThat(all.text("system")).isEqualTo(SpdSimulator.SYSTEM);
            assertThat(all.json().path("materials").size()).isEqualTo(2);
            assertThat(all.json().path("materials").get(0).path("highValueFlag").asInt()).isEqualTo(1);
            assertThat(all.json().path("materials").get(0).path("udiDi").asText()).isNotBlank();

            Response filtered = TestHttp.get(simulator.baseUrl() + "/spd/v1/materials?keyword=SPD-M-0002");
            assertThat(filtered.json().path("total").asInt()).isEqualTo(1);
        } finally {
            simulator.close();
        }
    }

    @Test
    void syncIsIdempotentPerKeyAndRequiresKey() throws Exception {
        SpdSimulator simulator = new SpdSimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            String body = "{\"idempotencyKey\":\"SYNTH-KEY-1\",\"since\":\"2026-11-01T00:00:00+08:00\"}";
            Response first = TestHttp.post(simulator.baseUrl() + "/spd/v1/sync", body);
            Response second = TestHttp.post(simulator.baseUrl() + "/spd/v1/sync", body);

            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).isEqualTo(second.body());
            assertThat(first.json().path("acknowledged").asBoolean()).isTrue();
            assertThat(first.json().path("pulled").asInt()).isEqualTo(SpdSimulator.PULLED_COUNT);

            Response newKey = TestHttp.post(simulator.baseUrl() + "/spd/v1/sync",
                    "{\"idempotencyKey\":\"SYNTH-KEY-2\"}");
            assertThat(newKey.text("syncNo")).isEqualTo("SPD-SYNC-0002");
            assertThat(newKey.text("syncNo")).isNotEqualTo(first.text("syncNo"));

            Response missingKey = TestHttp.post(simulator.baseUrl() + "/spd/v1/sync", "{\"since\":\"x\"}");
            assertThat(missingKey.status()).isEqualTo(400);
            assertThat(missingKey.text("code")).isEqualTo("INVALID_SYNC_REQUEST");
            assertThat(TestHttp.post(simulator.baseUrl() + "/spd/v1/sync", "").status()).isEqualTo(400);
        } finally {
            simulator.close();
        }
    }

    @Test
    void adapterStatusAndMaterialSnapshotAreStable() throws Exception {
        SpdSimulator simulator = new SpdSimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            Response status = TestHttp.get(simulator.baseUrl() + "/spd/v1/adapter-status");
            assertThat(status.json().path("online").asBoolean()).isTrue();
            assertThat(status.json().path("pendingReceipts").asInt()).isZero();
            assertThat(status.text("lastSyncAt")).isEqualTo(SpdSimulator.LAST_SYNC_AT);
        } finally {
            simulator.close();
        }

        List<SpdMaterial> materials = simulator.materials();
        assertThat(materials).hasSize(2);
        assertThat(materials.get(1).materialCode()).isEqualTo("SPD-M-0002");
        assertThat(materials.get(1).highValueFlag()).isZero();
    }

    @Test
    void failureModesExposeUpstreamErrorAndMalformedJson() throws Exception {
        SimulatorConfig.Endpoint endpoint = TestHttp.endpoint(0);
        endpoint.setFailureMode(FailureMode.HTTP_ERROR);
        endpoint.setErrorStatus(503);
        SpdSimulator simulator = new SpdSimulator(endpoint);
        simulator.start();
        try {
            assertThat(TestHttp.get(simulator.baseUrl() + "/spd/v1/materials").status()).isEqualTo(503);
        } finally {
            simulator.close();
        }

        SimulatorConfig.Endpoint malformedEndpoint = TestHttp.endpoint(0);
        malformedEndpoint.setFailureMode(FailureMode.MALFORMED_JSON);
        SpdSimulator malformed = new SpdSimulator(malformedEndpoint);
        malformed.start();
        try {
            assertThat(TestHttp.get(malformed.baseUrl() + "/spd/v1/materials").status()).isEqualTo(200);
            assertThat(TestHttp.get(malformed.baseUrl() + "/spd/v1/materials").body())
                    .isEqualTo("{\"broken\": ");
        } finally {
            malformed.close();
        }
    }
}
