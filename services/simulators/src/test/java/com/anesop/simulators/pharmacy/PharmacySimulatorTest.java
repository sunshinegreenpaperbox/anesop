package com.anesop.simulators.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.simulators.FailureMode;
import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.pharmacy.PharmacySimulator.PharmacyDrug;
import com.anesop.simulators.support.TestHttp;
import com.anesop.simulators.support.TestHttp.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 药房模拟器：目录/库存/发药成功与失败/在线状态/故障注入。 */
class PharmacySimulatorTest {

    @Test
    void catalogSupportsKeywordFilterAndUsesPharmacySideFieldNames() throws Exception {
        PharmacySimulator simulator = new PharmacySimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            Response all = TestHttp.get(simulator.baseUrl() + "/pharmacy/v1/drugs");
            assertThat(all.status()).isEqualTo(200);
            assertThat(all.text("system")).isEqualTo(PharmacySimulator.SYSTEM);
            assertThat(all.json().path("drugs").size()).isEqualTo(3);

            Response filtered = TestHttp.get(simulator.baseUrl() + "/pharmacy/v1/drugs?keyword=%E8%88%92%E8%8A%AC");
            assertThat(filtered.json().path("total").asInt()).isEqualTo(1);
            assertThat(filtered.json().path("drugs").get(0).path("drugCode").asText())
                    .isEqualTo("PMS-D-0001");
            assertThat(filtered.json().path("drugs").get(0).path("narcoticFlag").asInt()).isEqualTo(1);
            assertThat(filtered.json().path("drugs").get(0).has("aliasList")).isTrue();
        } finally {
            simulator.close();
        }
    }

    @Test
    void stockReturnsBatchAndUnknownDrugIs404() throws Exception {
        PharmacySimulator simulator = new PharmacySimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            Response stock = TestHttp.get(simulator.baseUrl() + "/pharmacy/v1/stock/PMS-D-0001");
            assertThat(stock.status()).isEqualTo(200);
            assertThat(stock.text("batchNo")).isEqualTo(PharmacySimulator.BATCH_NO);
            assertThat(stock.json().path("quantity").asInt()).isEqualTo(PharmacySimulator.STOCK_QUANTITY);
            assertThat(stock.text("location")).isEqualTo("药房（合成）");

            assertThat(TestHttp.get(simulator.baseUrl() + "/pharmacy/v1/stock/PMS-D-9999")
                    .status()).isEqualTo(404);
            assertThat(TestHttp.get(simulator.baseUrl() + "/pharmacy/v1/stock/")
                    .status()).isEqualTo(404);
        } finally {
            simulator.close();
        }
    }

    @Test
    void dispenseReturnsResultAndRejectsInsufficientStockAndBadBodies() throws Exception {
        PharmacySimulator simulator = new PharmacySimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            Response dispensed = TestHttp.post(simulator.baseUrl() + "/pharmacy/v1/dispense",
                    "{\"prescriptionNo\":\"RX-SYNTH-0001\",\"drugCode\":\"PMS-D-0001\",\"quantity\":2}");
            assertThat(dispensed.status()).isEqualTo(200);
            assertThat(dispensed.json().path("dispensed").asBoolean()).isTrue();
            assertThat(dispensed.json().path("quantity").asInt()).isEqualTo(2);
            assertThat(dispensed.text("batchNo")).isEqualTo(PharmacySimulator.BATCH_NO);

            Response insufficient = TestHttp.post(simulator.baseUrl() + "/pharmacy/v1/dispense",
                    "{\"prescriptionNo\":\"RX-SYNTH-0002\",\"drugCode\":\"PMS-D-0001\",\"quantity\":9999}");
            assertThat(insufficient.status()).isEqualTo(409);
            assertThat(insufficient.text("code")).isEqualTo("INSUFFICIENT_STOCK");
            assertThat(insufficient.json().path("requested").asInt()).isEqualTo(9999);

            assertThat(TestHttp.post(simulator.baseUrl() + "/pharmacy/v1/dispense",
                    "{\"prescriptionNo\":\"RX-SYNTH-0003\",\"drugCode\":\"PMS-D-9999\",\"quantity\":1}")
                    .status()).isEqualTo(404);
            assertThat(TestHttp.post(simulator.baseUrl() + "/pharmacy/v1/dispense",
                    "{\"drugCode\":\"PMS-D-0001\",\"quantity\":0}").text("code"))
                    .isEqualTo("INVALID_DISPENSE_REQUEST");
            assertThat(TestHttp.post(simulator.baseUrl() + "/pharmacy/v1/dispense", "")
                    .text("code")).isEqualTo("INVALID_DISPENSE_REQUEST");
        } finally {
            simulator.close();
        }
    }

    @Test
    void adapterStatusExposesOnlineAndPendingCount() throws Exception {
        PharmacySimulator simulator = new PharmacySimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            Response status = TestHttp.get(simulator.baseUrl() + "/pharmacy/v1/adapter-status");
            assertThat(status.status()).isEqualTo(200);
            assertThat(status.json().path("online").asBoolean()).isTrue();
            assertThat(status.json().path("pendingOrders").asInt()).isZero();
            assertThat(status.text("lastSyncAt")).isEqualTo(PharmacySimulator.LAST_SYNC_AT);
            assertThat(status.text("systemName")).isEqualTo(PharmacySimulator.SYSTEM_NAME);
        } finally {
            simulator.close();
        }
    }

    @Test
    void httpErrorModeMakesAdapterUnavailableForDegradeTests() throws Exception {
        SimulatorConfig.Endpoint endpoint = TestHttp.endpoint(0);
        endpoint.setFailureMode(FailureMode.HTTP_ERROR);
        endpoint.setErrorStatus(500);
        PharmacySimulator simulator = new PharmacySimulator(endpoint);
        simulator.start();
        try {
            Response response = TestHttp.get(simulator.baseUrl() + "/pharmacy/v1/adapter-status");
            assertThat(response.status()).isEqualTo(500);
            assertThat(response.text("code")).isEqualTo("SIMULATED_UPSTREAM_ERROR");
        } finally {
            simulator.close();
        }
    }

    @Test
    void drugCatalogSnapshotIsStableAndSynthetic() {
        PharmacySimulator simulator = new PharmacySimulator(TestHttp.endpoint(0));
        List<PharmacyDrug> drugs = simulator.drugs();

        assertThat(drugs).hasSize(3);
        assertThat(drugs.get(0).aliasList()).containsExactly("舒芬（药房模拟）");
        assertThat(drugs.get(2).narcoticFlag()).isZero();
    }
}
