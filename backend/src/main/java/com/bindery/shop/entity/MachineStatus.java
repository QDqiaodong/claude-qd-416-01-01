package com.bindery.shop.entity;

/**
 * 机台状态只有三个固定取值，后端只认这三个，前端下拉也只能给这三个。
 * 历史上自由输入写进库里的值（检修/停机/随手词）一律视为非法脏值，
 * 业务流转一律拒绝，只能通过报修 → 修好的路径收敛回合法取值。
 */
public final class MachineStatus {
    public static final String IDLE = "空闲";
    public static final String RUNNING = "运行";
    public static final String REPAIR = "维修";

    private MachineStatus() {
    }

    public static boolean isValid(String status) {
        return IDLE.equals(status) || RUNNING.equals(status) || REPAIR.equals(status);
    }

    public static String requireValid(String status) {
        if (!isValid(status))
            throw new IllegalArgumentException(
                    "机器状态必须是 空闲/运行/维修 之一，收到非法取值：" + status);
        return status;
    }
}
