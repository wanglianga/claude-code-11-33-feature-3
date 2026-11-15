package com.trace.recall.web;

import com.trace.recall.ingestion.IngestionService;
import com.trace.recall.release.ReleaseService;
import com.trace.recall.verification.VerificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内部质量工作台 /api：无 Key，可查看原始载荷。
 */
@RestController
@RequestMapping("/api")
public class InternalController {

    private final IngestionService ingestionService;
    private final VerificationService verificationService;
    private final ReleaseService releaseService;

    public InternalController(IngestionService ingestionService, VerificationService verificationService,
                              ReleaseService releaseService) {
        this.ingestionService = ingestionService;
        this.verificationService = verificationService;
        this.releaseService = releaseService;
    }

    @GetMapping("/source-packages")
    public List<Map<String, Object>> sourcePackages() {
        return ingestionService.listPackages(false);
    }

    @GetMapping("/source-packages/{code}")
    public Map<String, Object> sourcePackage(@PathVariable String code) {
        return ingestionService.getPackage(code);
    }

    @GetMapping("/meta/inspection-versions")
    public List<Map<String, Object>> inspectionVersions() {
        return ingestionService.listInspectionVersions();
    }

    @GetMapping("/meta/parse-rules")
    public List<Map<String, Object>> parseRules() {
        return ingestionService.listParseRules();
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        return ingestionService.listBatches();
    }

    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody Map<String, Object> body) {
        String inspectionVersion = required(body, "inspectionCorrectionVersion");
        String cutoffAt = required(body, "cutoffAt");
        String createdBy = String.valueOf(body.getOrDefault("createdBy", "质量分析员-张某"));
        return verificationService.createRun(inspectionVersion, cutoffAt, createdBy);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return verificationService.listRuns();
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable long id) {
        return verificationService.getRun(id);
    }

    @GetMapping("/runs/{id}/batches/{batchCode}/trace")
    public Map<String, Object> trace(@PathVariable long id, @PathVariable String batchCode) {
        return verificationService.getTrace(id, batchCode);
    }

    @PostMapping("/runs/{id}/publish")
    public Map<String, Object> publish(@PathVariable long id) {
        return verificationService.publish(id);
    }

    @GetMapping("/releases")
    public List<Map<String, Object>> releases() {
        return releaseService.listReleases();
    }

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        return v.toString();
    }
}
