package com.trace.recall.web;

import com.trace.recall.substitution.AnalysisPublicationService;
import com.trace.recall.substitution.SubstitutionQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 替代目录语义对齐与适用性声明工作台 /api/substitution/**（内网，无 Key）。
 * 本控制器只输出质量分析依据，不提供任何推荐、库存、需求或调拨能力。
 */
@RestController
@RequestMapping("/api/substitution")
public class SubstitutionController {

    private final SubstitutionQueryService queryService;
    private final AnalysisPublicationService publicationService;

    public SubstitutionController(SubstitutionQueryService queryService,
                                  AnalysisPublicationService publicationService) {
        this.queryService = queryService;
        this.publicationService = publicationService;
    }

    @GetMapping("/scopes")
    public List<Map<String, Object>> scopes() {
        return queryService.listScopes();
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents() {
        return queryService.listDocuments();
    }

    @GetMapping("/entries")
    public List<Map<String, Object>> entries() {
        return queryService.listEntries();
    }

    @GetMapping("/statements")
    public List<Map<String, Object>> statements() {
        return queryService.listStatements();
    }

    @GetMapping("/conflicts")
    public List<Map<String, Object>> conflicts() {
        return queryService.listConflicts();
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> requests() {
        return publicationService.listRequests();
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> request(@PathVariable long id) {
        return publicationService.getRequest(id);
    }

    @PostMapping("/requests")
    public Map<String, Object> createRequest(@RequestBody Map<String, Object> body) {
        String scopeCode = String.valueOf(body.getOrDefault("scopeCode", "SCOPE-2026-11"));
        String analysisDate = required(body, "analysisDate");
        String createdBy = String.valueOf(body.getOrDefault("createdBy", "质量分析员-张某"));
        return publicationService.createRequest(scopeCode, analysisDate, createdBy);
    }

    @PostMapping("/statements/{id}/review")
    public Map<String, Object> review(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String decision = required(body, "decision");
        String reviewer = String.valueOf(body.getOrDefault("reviewer", "质量审核员-李某"));
        String note = String.valueOf(body.getOrDefault("reviewNote", ""));
        return queryService.reviewStatement(id, decision, reviewer, note);
    }

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        return v.toString();
    }
}
