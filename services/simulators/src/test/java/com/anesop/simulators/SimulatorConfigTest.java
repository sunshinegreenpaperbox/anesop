package com.anesop.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 模拟器配置校验（fail-closed：写错配置必须启动失败，不能静默取默认值）。 */
class SimulatorConfigTest {

    @Test
    void defaultsUseDocumentedPortsAndNoFailureInjection() {
        SimulatorConfig config = SimulatorConfig.defaults().validate();

        assertThat(config.getHis().getPort()).isEqualTo(SimulatorConfig.DEFAULT_HIS_PORT);
        assertThat(config.getPharmacy().getPort()).isEqualTo(SimulatorConfig.DEFAULT_PHARMACY_PORT);
        assertThat(config.getSpd().getPort()).isEqualTo(SimulatorConfig.DEFAULT_SPD_PORT);
        assertThat(config.getHis().getFailureMode()).isEqualTo(FailureMode.NONE);
        assertThat(config.getHis().getErrorStatus()).isEqualTo(500);
        assertThat(config.getHis().getLatencyMs()).isZero();
        assertThat(config.getHis().getFailFirstRequests()).isZero();
    }

    @Test
    void nullEndpointFallsBackToDefaultPort() {
        SimulatorConfig config = SimulatorConfig.defaults();
        config.setHis(null);
        config.setPharmacy(null);
        config.setSpd(null);

        config.validate();

        assertThat(config.getHis().getPort()).isEqualTo(SimulatorConfig.DEFAULT_HIS_PORT);
        assertThat(config.getPharmacy().getPort()).isEqualTo(SimulatorConfig.DEFAULT_PHARMACY_PORT);
        assertThat(config.getSpd().getPort()).isEqualTo(SimulatorConfig.DEFAULT_SPD_PORT);
    }

    @Test
    void nullFailureModeIsNormalizedToNone() {
        SimulatorConfig config = SimulatorConfig.defaults();
        SimulatorConfig.Endpoint endpoint = new SimulatorConfig.Endpoint(0);
        endpoint.setFailureMode(null);
        config.setHis(endpoint);

        config.validate();

        assertThat(config.getHis().getFailureMode()).isEqualTo(FailureMode.NONE);
    }

    @Test
    void rejectsIllegalPortLatencyFailCountAndStatus() {
        SimulatorConfig portTooHigh = SimulatorConfig.defaults();
        portTooHigh.setHis(new SimulatorConfig.Endpoint(70000));
        assertThatThrownBy(portTooHigh::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("his.port");

        SimulatorConfig negativeLatency = SimulatorConfig.defaults();
        SimulatorConfig.Endpoint slow = new SimulatorConfig.Endpoint(0);
        slow.setLatencyMs(-1);
        negativeLatency.setPharmacy(slow);
        assertThatThrownBy(negativeLatency::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pharmacy.latencyMs");

        SimulatorConfig negativeFailCount = SimulatorConfig.defaults();
        SimulatorConfig.Endpoint failing = new SimulatorConfig.Endpoint(0);
        failing.setFailFirstRequests(-2);
        negativeFailCount.setSpd(failing);
        assertThatThrownBy(negativeFailCount::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spd.failFirstRequests");

        SimulatorConfig illegalStatus = SimulatorConfig.defaults();
        SimulatorConfig.Endpoint status = new SimulatorConfig.Endpoint(0);
        status.setErrorStatus(200);
        illegalStatus.setHis(status);
        assertThatThrownBy(illegalStatus::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("his.errorStatus");
    }

    @Test
    void endpointDefaultConstructorKeepsPortZeroForEphemeralBinding() {
        SimulatorConfig.Endpoint endpoint = new SimulatorConfig.Endpoint();

        assertThat(endpoint.getPort()).isZero();
        assertThat(endpoint.getFailureMode()).isEqualTo(FailureMode.NONE);
    }
}
