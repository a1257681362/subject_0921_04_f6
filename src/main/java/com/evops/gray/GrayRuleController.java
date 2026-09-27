package com.evops.gray;

import com.evops.common.ApiResponse;
import com.evops.gray.dto.GrayRuleRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/gray/rules")
public class GrayRuleController {

    private final GrayRuleService grayRuleService;

    public GrayRuleController(GrayRuleService grayRuleService) {
        this.grayRuleService = grayRuleService;
    }

    /** 新增或调整灰度规则：试点放单网点编码，推全放 '*'；status ON/OFF 开关 */
    @PostMapping
    public ApiResponse<GrayRule> upsert(@Valid @RequestBody GrayRuleRequest request) {
        return ApiResponse.ok(grayRuleService.upsert(request));
    }

    /** 灰度规则列表 */
    @GetMapping
    public ApiResponse<List<GrayRule>> list(@RequestParam(required = false) String scene) {
        return ApiResponse.ok(grayRuleService.list(scene));
    }
}
