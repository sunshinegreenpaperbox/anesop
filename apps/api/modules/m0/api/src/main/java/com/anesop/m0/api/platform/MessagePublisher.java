package com.anesop.m0.api.platform;

import com.anesop.contracts.platform.model.MessageType;
import java.util.Map;
import java.util.UUID;

/**
 * 站内消息写入入口（内部服务，不暴露 HTTP 端点；M8-M0-04）。
 *
 * <p>消息由<b>领域事件/系统广播</b>写入：业务模块在状态变更后调用本接口，或由系统任务广播
 * 维护通知。设计约束：
 * <ul>
 *   <li><b>只允许注册表模板</b>：调用方提交 {@link MessageTemplates} 中已登记的
 *       {@code templateCode}，标题由服务端按模板渲染；接口<b>不接收任意标题</b>，
 *       避免成为“任意通知”通道（评审 S-4）；</li>
 *   <li>参数键必须属于该模板的允许集合（如 {@code roomNo}/{@code startAt}），
 *       值必须是字符串且 ≤200 字符；</li>
 *   <li><b>禁止 PHI</b>：调用方不得把患者姓名、住院号、证件号、手机号、诊断或临床自由文本
 *       放进参数；本接口做结构、长度与字符集兜底，合规责任在调用方与代码评审。</li>
 * </ul>
 */
public interface MessagePublisher {

    /**
     * 写入一条消息；标题由 {@link MessageTemplates} 注册表按 TemplateCode 渲染。
     *
     * @return 新消息标识
     * @throws M0ValidationException 收件人为空、模板未登记、类型不匹配、参数不合规
     */
    UUID publish(MessageCommand command);

    /**
     * 消息写入命令（不可变）。
     *
     * @param recipientUserId 收件人（必填）
     * @param type            消息类型（必须与注册表模板类型一致；用于防御性显式声明）
     * @param templateCode    {@link MessageTemplates} 中已登记的模板编码
     * @param params          模板参数（键必须是该模板允许的集合，值 ≤200 字符串）
     */
    record MessageCommand(UUID recipientUserId, MessageType type, String templateCode,
            Map<String, String> params) {

        /** 防御性拷贝：参数 Map 不可变，避免调用方后续修改影响已入队/已落库内容。 */
        public MessageCommand {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
