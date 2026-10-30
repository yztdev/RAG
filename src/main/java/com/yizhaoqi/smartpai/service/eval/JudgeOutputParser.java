package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * 防御性解析评委输出：容忍 ```json 代码块和前后多余文字，
 * 但 score 必须是 1-5 的整数、correct 必须是布尔值，否则判为解析失败（该用例进入人工复核）。
 */
public final class JudgeOutputParser {

    private static final int MAX_RAW_CHARS = 500;
    private static final int MAX_REASON_CHARS = 300;

    private JudgeOutputParser() {
    }

    public static JudgeVerdict parse(String content, ObjectMapper objectMapper) {
        if (content == null || content.isBlank()) {
            return JudgeVerdict.failed("评委输出为空", content);
        }
        String raw = truncate(content, MAX_RAW_CHARS);
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return JudgeVerdict.failed("评委输出中没有 JSON 对象", raw);
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(content.substring(start, end + 1));
        } catch (Exception e) {
            return JudgeVerdict.failed("评委输出不是合法 JSON", raw);
        }
        if (node == null || !node.isObject()) {
            return JudgeVerdict.failed("评委输出不是 JSON 对象", raw);
        }

        Integer score = parseScore(node.get("score"));
        if (score == null) {
            return JudgeVerdict.failed("score 缺失或不是 1-5 的整数", raw);
        }
        Boolean correct = parseBoolean(node.get("correct"));
        if (correct == null) {
            return JudgeVerdict.failed("correct 缺失或不是布尔值", raw);
        }
        return JudgeVerdict.of(score, correct, truncate(node.path("reason").asText(""), MAX_REASON_CHARS));
    }

    static Integer parseScore(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble();
        } else if (node.isTextual()) {
            try {
                value = Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        if (value != Math.rint(value) || value < 1 || value > 5) {
            return null;
        }
        return (int) value;
    }

    static Boolean parseBoolean(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String text = node.asText().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(text)) {
                return true;
            }
            if ("false".equals(text)) {
                return false;
            }
        }
        return null;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
