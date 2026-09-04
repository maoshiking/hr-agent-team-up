package com.hragent.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：装所有可用工具，按名字调用，并生成给模型的 function 定义。
 * （function-calling 基础设施）
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    /** 执行一个工具，返回它的结果 Map。 */
    public Map<String, Object> call(String name, Map<String, Object> args) throws Exception {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具：" + name);
        }
        return tool.run(args);
    }

    /** 生成 OpenAI/DeepSeek 风格的 tools 数组，放进请求体。 */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.put("parameters", tool.parameters());
            Map<String, Object> function = new HashMap<>();
            function.put("type", "function");
            function.put("function", fn);
            defs.add(function);
        }
        return defs;
    }
}
