package com.hragent.tool;

import java.util.Map;

/**
 * 所有"真工具"都要实现的接口（function-calling 用的函数）。
 * 目前提供两个内置工具：doc_writer（把成果落成文件）、resume_parser（解析简历文本）。
 */
public interface Tool {

    /** 工具名，例如 "doc_writer"。 */
    String name();

    /** 给模型的描述：这个工具是干嘛的、什么时候用。 */
    String description();

    /** 参数 JSON Schema（OpenAI 风格：{type:object, properties:{...}, required:[...]}）。 */
    Map<String, Object> parameters();

    /** 真正执行工具，返回结果 Map。 */
    Map<String, Object> run(Map<String, Object> args) throws Exception;
}
