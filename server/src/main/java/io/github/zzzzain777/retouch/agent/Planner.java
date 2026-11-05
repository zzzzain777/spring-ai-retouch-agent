package io.github.zzzzain777.retouch.agent;

import java.util.List;

/**
 * 规划模型的边界：把一句指令与画布摘要变成工具调用“提议”，本身不执行任何工具，也不碰数据库。
 * 提议还要经过 {@link PlanValidator} 才会成为计划。
 */
public interface Planner {

    Proposal plan(String goal, String canvas);

    /** 模型给出的一次工具调用，参数是模型原样给的 JSON 文本。 */
    record Call(String tool, String arguments) {
    }

    /**
     * @param text   模型给的文字（无工具调用时是给用户的一句话）
     * @param calls  按顺序给出的工具调用
     * @param trace  记录进轨迹的模型名、耗时、token 用量等
     */
    record Proposal(String text, List<Call> calls, PlannerTrace trace) {
    }

    /** 规划模型未配置：轮次直接失败，不派发任何工具。 */
    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
