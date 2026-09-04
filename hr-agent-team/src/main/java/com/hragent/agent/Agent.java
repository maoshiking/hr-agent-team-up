package com.hragent.agent;

import java.util.Map;

/**
 * 所有数字员工都必须实现的接口（相当于给每个 agent 定的"统一契约"）。
 *
 * 铁律：
 *  1. 方法签名固定为 run(Map payload) → Map，不要改；
 *  2. 吃进一个 JSON（Map），吐出一个 JSON（Map），字段照契约，不自创；
 *  3. 调模型统一用 com.hragent.common.DeepSeekClient，不自己另写。
 */
public interface Agent {

    Map<String, Object> run(Map<String, Object> payload);
}
