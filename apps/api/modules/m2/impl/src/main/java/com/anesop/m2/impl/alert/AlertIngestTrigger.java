package com.anesop.m2.impl.alert;

import com.anesop.m2.impl.acquisition.VitalsIngestedEvent;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.DeviceRegistryRow;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.DeviceRegistryMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集落库后的告警评估触发（M3-M2-02 评审 B-1，ADR-0017）。
 *
 * <p>按设备台账（m2 自己的 {@code device_registry}）解析 roomId，再匹配本模块
 * {@code anesthesia_record.room_id} 快照的进行中记录；<b>不跨模块读 m1 表</b>。
 * 只评估 {@code status=ONGOING 且 started_at IS NOT NULL、ended_at IS NULL} 的记录
 * （DRAFT 不评估）。每台设备一次事件只对该术间至多一张活动记录做增量评估。
 *
 * <p>独立事务（REQUIRES_NEW）：ingest 事务已提交，评估失败不能回滚采集数据。
 */
@Component
public class AlertIngestTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(AlertIngestTrigger.class);

    private final DeviceRegistryMapper registryMapper;
    private final AnesthesiaRecordMapper recordMapper;
    private final AlertEvaluator evaluator;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/组件由容器管理，不修改其内部状态")
    public AlertIngestTrigger(DeviceRegistryMapper registryMapper,
            AnesthesiaRecordMapper recordMapper, AlertEvaluator evaluator) {
        this.registryMapper = registryMapper;
        this.recordMapper = recordMapper;
        this.evaluator = evaluator;
    }

    /**
     * 对一个设备触发其所在术间活动记录的增量评估。
     *
     * @return 新增告警数（设备未绑定术间、无活动记录或 DRAFT 时为 0）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int evaluateForDevice(VitalsIngestedEvent event) {
        DeviceRegistryRow device = registryMapper.selectByKey(event.gatewayId(), event.deviceId());
        if (device == null || device.getRoomId() == null) {
            return 0;
        }
        int created = 0;
        for (AnesthesiaRecordEntity record : recordMapper.selectOngoingByRoom(device.getRoomId())) {
            created += evaluator.evaluate(record, AlertEvaluator.EvaluationTrigger.system())
                    .created();
        }
        if (created > 0) {
            LOG.info("采集落库后评估产生新告警：batchKey={}，count={}", event.batchKey(), created);
        }
        return created;
    }
}
