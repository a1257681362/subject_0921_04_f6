package com.evops.rental;

import com.evops.common.ApiResponse;
import com.evops.rental.dto.TransitionRuleRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 订单状态机规则管理：合法路径集中定义，配置驱动。
 * 新增状态/路径只插规则行即生效，不改代码；
 * 内置主流程路径（交车/还车/归档/冲销的种子路径）禁改，防现场误配。
 */
@RestController
@RequestMapping("/api/orders/state-machine")
public class RentalStateMachineController {

    private final OrderStateMachine stateMachine;

    public RentalStateMachineController(OrderStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 合法路径全量列表（含停用），集中定义全员可查 */
    @GetMapping("/transitions")
    public ApiResponse<List<TransitionRule>> list() {
        return ApiResponse.ok(stateMachine.listRules());
    }

    /** 新增/调整流转路径：同 (源状态, 动作) 已存在则更新；保留动作通用入口恒不开放 */
    @PostMapping("/transitions")
    public ApiResponse<TransitionRule> upsert(@Valid @RequestBody TransitionRuleRequest request) {
        return ApiResponse.ok(stateMachine.upsertRule(request));
    }
}
