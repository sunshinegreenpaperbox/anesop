package com.anesop.m2.impl.alert;

import com.anesop.m2.impl.acquisition.VitalsIngestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 采集落库成功后的告警评估监听器（M3-M2-02 评审 B-1，ADR-0017）。
 *
 * <p>只在 ingest 事务提交后（AFTER_COMMIT）评估，采集回滚不会产生告警；评估使用独立事务，
 * 失败只记录 WARN（不含患者标识/报文内容），不影响采集响应与已落库体征。
 */
@Component
public class VitalIngestAlertListener {

    private static final Logger LOG = LoggerFactory.getLogger(VitalIngestAlertListener.class);

    private final AlertIngestTrigger trigger;

    public VitalIngestAlertListener(AlertIngestTrigger trigger) {
        this.trigger = trigger;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVitalsIngested(VitalsIngestedEvent event) {
        if (event == null || event.insertedRows() <= 0) {
            return;
        }
        try {
            trigger.evaluateForDevice(event);
        } catch (RuntimeException failure) {
            // 告警评估失败不影响采集：体征已落库，前端轮询 + 手动 evaluate 可补偿。
            LOG.warn("采集落库后告警评估失败（已忽略，不影响采集）：batchKey={}，reason={}",
                    event.batchKey(), failure.getClass().getSimpleName());
        }
    }
}
