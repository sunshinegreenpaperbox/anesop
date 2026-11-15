package com.anesop.simulators.his;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.simulators.FailureMode;
import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.his.HisSimulator.HisApplication;
import com.anesop.simulators.his.HisSimulator.HisPatient;
import com.anesop.simulators.support.TestHttp;
import com.anesop.simulators.support.TestHttp.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

/** HIS 模拟器：确定性数据、患者主索引一致性、回调、参数校验与故障注入。 */
class HisSimulatorTest {

    @Test
    void applicationsAreDeterministicPerSeedAndShapedAsHisFields() throws Exception {
        HisSimulator simulator = new HisSimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            Response first = TestHttp.get(simulator.baseUrl() + "/his/v1/surgery-applications?count=3&seed=7");
            Response second = TestHttp.get(simulator.baseUrl() + "/his/v1/surgery-applications?count=3&seed=7");

            assertThat(first.status()).isEqualTo(200);
            assertThat(first.json().path("simulated").asBoolean()).isTrue();
            assertThat(first.text("source")).isEqualTo(HisSimulator.SOURCE);
            assertThat(first.json().path("applications").size()).isEqualTo(3);
            assertThat(first.json().path("applications")).isEqualTo(second.json().path("applications"));

            List<HisApplication> generated = HisSimulator.applications(3, 7L);
            String applicationNo = first.json().path("applications").get(0).path("applicationNo").asText();
            assertThat(applicationNo).isEqualTo(generated.get(0).applicationNo());
            assertThat(applicationNo).startsWith(HisSimulator.APPLICATION_PREFIX);
            assertThat(first.json().path("applications").get(0).path("patient").path("sex").asText())
                    .isIn("F", "M", "U");
            assertThat(first.json().path("applications").get(0).path("urgency").asText())
                    .isIn("择期", "限期", "急诊");
        } finally {
            simulator.close();
        }
    }

    @Test
    void patientIndexAgreesWithApplicationPatientFields() throws Exception {
        HisSimulator simulator = new HisSimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            HisApplication application = HisSimulator.applications(1, 11L).get(0);
            Response patient = TestHttp.get(simulator.baseUrl() + "/his/v1/patients/"
                    + application.patient().patientNo());

            assertThat(patient.status()).isEqualTo(200);
            HisPatient indexed = new HisPatient(
                    patient.text("patientNo"), patient.text("patientName"), patient.text("sex"),
                    patient.text("birthday"), patient.json().path("age").asInt(),
                    patient.text("admissionNo"), patient.text("departmentCode"),
                    patient.text("department"), patient.text("ward"), patient.text("bed"));
            assertThat(indexed).isEqualTo(application.patient());
        } finally {
            simulator.close();
        }
    }

    @Test
    void rejectsInvalidCountSeedAndUnknownPatient() throws Exception {
        HisSimulator simulator = new HisSimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            assertThat(TestHttp.get(simulator.baseUrl() + "/his/v1/surgery-applications?count=0")
                    .text("code")).isEqualTo("INVALID_COUNT");
            assertThat(TestHttp.get(simulator.baseUrl() + "/his/v1/surgery-applications?count=6")
                    .status()).isEqualTo(400);
            assertThat(TestHttp.get(simulator.baseUrl() + "/his/v1/surgery-applications?count=abc")
                    .text("code")).isEqualTo("INVALID_COUNT");
            assertThat(TestHttp.get(simulator.baseUrl() + "/his/v1/surgery-applications?count=2&seed=abc")
                    .text("code")).isEqualTo("INVALID_SEED");
            assertThat(TestHttp.get(simulator.baseUrl() + "/his/v1/patients/SYNTH-P-1")
                    .status()).isEqualTo(404);
            assertThat(TestHttp.get(simulator.baseUrl() + "/his/v1/patients/NOT-A-PATIENT")
                    .status()).isEqualTo(404);
        } finally {
            simulator.close();
        }
    }

    @Test
    void acceptsCreationAndChangeCallbacksAndRejectsInvalidBodies() throws Exception {
        HisSimulator simulator = new HisSimulator(TestHttp.endpoint(0));
        simulator.start();
        try {
            String applicationNo = HisSimulator.applications(1, 3L).get(0).applicationNo();
            Response accepted = TestHttp.post(simulator.baseUrl()
                    + "/his/v1/surgery-applications/" + applicationNo + "/status", "{\"status\":\"ACCEPTED\"}");
            assertThat(accepted.status()).isEqualTo(200);
            assertThat(accepted.json().path("accepted").asBoolean()).isTrue();
            assertThat(accepted.text("receivedAt")).isNotBlank();

            Response rejected = TestHttp.post(simulator.baseUrl()
                    + "/his/v1/surgery-applications/" + applicationNo + "/status", "{\"status\":\"REJECTED\"}");
            assertThat(rejected.json().path("accepted").asBoolean()).isFalse();

            Response changed = TestHttp.post(simulator.baseUrl()
                    + "/his/v1/surgery-applications/" + applicationNo + "/change",
                    "{\"reason\":\"术间调整（SYNTHETIC）\"}");
            assertThat(changed.status()).isEqualTo(200);
            assertThat(changed.json().path("changed").asBoolean()).isTrue();

            assertThat(TestHttp.post(simulator.baseUrl()
                    + "/his/v1/surgery-applications/" + applicationNo + "/status", "{\"status\":\"MAYBE\"}")
                    .text("code")).isEqualTo("INVALID_STATUS");
            assertThat(TestHttp.post(simulator.baseUrl()
                    + "/his/v1/surgery-applications/" + applicationNo + "/status", "")
                    .status()).isEqualTo(400);
            assertThat(TestHttp.post(simulator.baseUrl()
                    + "/his/v1/surgery-applications/" + applicationNo + "/change", "{}")
                    .text("code")).isEqualTo("INVALID_CHANGE");
            assertThat(TestHttp.post(simulator.baseUrl()
                    + "/his/v1/surgery-applications/SYNTH-HIS-APP-9999/status", "{\"status\":\"ACCEPTED\"}")
                    .status()).isEqualTo(404);
            assertThat(TestHttp.post(simulator.baseUrl()
                    + "/his/v1/surgery-applications/BAD/change", "{\"reason\":\"x\"}")
                    .status()).isEqualTo(404);
        } finally {
            simulator.close();
        }
    }

    @Test
    void httpErrorModeIsVisibleAsSimulatedFailure() throws Exception {
        SimulatorConfig.Endpoint endpoint = TestHttp.endpoint(0);
        endpoint.setFailureMode(FailureMode.HTTP_ERROR);
        endpoint.setErrorStatus(503);
        HisSimulator simulator = new HisSimulator(endpoint);
        simulator.start();
        try {
            Response response = TestHttp.get(simulator.baseUrl() + "/his/v1/surgery-applications?count=1");
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.text("code")).isEqualTo("SIMULATED_UPSTREAM_ERROR");
            assertThat(simulator.requestCount()).isEqualTo(1);
        } finally {
            simulator.close();
        }
    }
}
