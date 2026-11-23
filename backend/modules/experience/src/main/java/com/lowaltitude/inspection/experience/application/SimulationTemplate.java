package com.lowaltitude.inspection.experience.application;

/**
 * 虚拟仿真用的 App 内任务模板（S5-4，默认决定 D7）：关卡由 App 内置，服务端只认关卡数。
 * 两个模板都是 3 关；改关卡数要和 App 的模板一起改。
 */
public enum SimulationTemplate {
    /** 机器人避障：拼积木程序让机器人绕过障碍到达终点。 */
    ROBOT_OBSTACLE(3),
    /** 无人机起降与悬停：拼程序起飞、升到目标高度悬停、降落。 */
    DRONE_HOVER(3);

    private final int levels;

    SimulationTemplate(int levels) {
        this.levels = levels;
    }

    public int levels() {
        return levels;
    }
}
