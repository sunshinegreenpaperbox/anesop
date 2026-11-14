package com.anesop.m5.api.dashboard;

/**
 * 看板术间投影（只读，无患者标识）。
 *
 * @param roomId   术间标识
 * @param roomNo   术间编号（院内唯一）
 * @param roomName 术间名称
 * @param deptId   所属科室标识；未设置为 null
 * @param deptName 所属科室名称；未设置为 null
 * @param siteId   所属院区标识；未设置为 null
 * @param status   M1 术间运行状态原文（IDLE/PREPARING/ANESTHESIA/SURGERY/PACU/CLEANING）
 * @param deleted  是否软删除（看板按“停用”计入 OUT_OF_SERVICE，【待确认】）
 */
public record DashboardRoomFact(
        String roomId,
        String roomNo,
        String roomName,
        String deptId,
        String deptName,
        String siteId,
        String status,
        boolean deleted) {
}
