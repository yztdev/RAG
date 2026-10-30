package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析 JSONL 数据集，每行一个用例：
 * {"id": "可选", "question": "...", "expectedFileMd5s": ["..."], "expectedFileNames": ["..."],
 *  "expectedEvidence": ["答案分块里的关键短语"], "referenceAnswer": "..."}
 * 空行和以 # 或 // 开头的注释行会被跳过；格式错误时报出行号。
 */
public final class EvalDatasetParser {

    private EvalDatasetParser() {
    }

    public static List<EvalCase> parse(Reader reader, ObjectMapper objectMapper, int maxCases) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        BufferedReader buffered = reader instanceof BufferedReader br ? br : new BufferedReader(reader);
        String line;
        int lineNo = 0;
        while ((line = buffered.readLine()) != null) {
            lineNo++;
            String trimmed = stripBom(line).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            if (cases.size() >= maxCases) {
                throw new IllegalArgumentException("数据集超过单次评测上限 " + maxCases + " 条");
            }
            cases.add(parseLine(trimmed, lineNo, objectMapper));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("数据集为空");
        }
        return cases;
    }

    static EvalCase parseLine(String line, int lineNo, ObjectMapper objectMapper) {
        JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("第 " + lineNo + " 行不是合法 JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行必须是 JSON 对象");
        }
        String question = text(node, "question");
        String reference = text(node, "referenceAnswer");
        if (question == null) {
            throw new IllegalArgumentException("第 " + lineNo + " 行缺少 question");
        }
        if (reference == null) {
            throw new IllegalArgumentException("第 " + lineNo + " 行缺少 referenceAnswer");
        }
        String id = text(node, "id");
        return new EvalCase(id == null ? "line-" + lineNo : id, question, strings(node, "expectedFileMd5s"),
                strings(node, "expectedFileNames"), reference, strings(node, "expectedEvidence"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isTextual()) {
            return value.asText().isBlank() ? List.of() : List.of(value.asText().trim());
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : value) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
