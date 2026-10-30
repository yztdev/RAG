package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工判定以 JSON 旁路文件保存在报告旁边：{报告文件名去掉 .json}.human-reviews.json。
 * 报告文件本身不改（保持评测当时的原始结果），人工判定可以多次追加、覆盖。
 * 写入先落临时文件再原子替换，写到一半进程退出也不会留下半个 JSON。
 */
final class HumanReviewStore {

    static final String SIDECAR_SUFFIX = ".human-reviews.json";

    private final ObjectMapper objectMapper;

    HumanReviewStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static Path sidecarPath(Path reportPath) {
        String name = reportPath.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
        return reportPath.resolveSibling(base + SIDECAR_SUFFIX);
    }

    Map<String, HumanVerdict> load(Path reportPath) throws IOException {
        Path sidecar = sidecarPath(reportPath);
        Map<String, HumanVerdict> reviews = new LinkedHashMap<>();
        if (!Files.isRegularFile(sidecar)) {
            return reviews;
        }
        JsonNode root = objectMapper.readTree(sidecar.toFile());
        JsonNode items = root.path("reviews");
        items.fields().forEachRemaining(entry ->
                reviews.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), HumanVerdict.class)));
        return reviews;
    }

    void save(Path reportPath, String runId, Map<String, HumanVerdict> reviews, JudgeHumanAgreement agreement)
            throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("runId", runId);
        document.put("reportFile", reportPath.getFileName().toString());
        document.put("updatedAt", Instant.now());
        document.put("reviews", reviews);
        document.put("agreement", agreement);

        Path sidecar = sidecarPath(reportPath);
        Path temp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), document);
        try {
            Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
