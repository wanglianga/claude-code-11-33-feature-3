package com.trace.recall.web;

import com.trace.recall.release.ReleaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部处置系统 /external：X-API-Key 由拦截器校验，仅返回脱敏摘要（字段白名单在 ReleaseService 强制）。
 */
@RestController
@RequestMapping("/external")
public class ExternalController {

    private final ReleaseService releaseService;

    public ExternalController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping("/releases")
    public List<Map<String, Object>> releases() {
        return releaseService.externalReleases();
    }

    @GetMapping("/releases/{code}")
    public Map<String, Object> summary(@PathVariable String code) {
        return releaseService.externalSummary(code);
    }
}
