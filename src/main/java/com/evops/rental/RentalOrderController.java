package com.evops.rental;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.ApiResponse;
import com.evops.common.BizException;
import com.evops.rental.dto.HandoverRequest;
import com.evops.rental.dto.OrderCreateRequest;
import com.evops.rental.dto.RenameCustomerRequest;
import com.evops.rental.dto.ReverseRequest;
import com.evops.rental.dto.ReturnSettleRequest;
import com.evops.rental.dto.TransitionRequest;
import com.evops.rental.fsm.RentalOrderTransition;
import com.evops.rental.fsm.RentalStateMachineConfig;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class RentalOrderController {

    private final RentalService rentalService;
    private final NameIndexService nameIndexService;

    public RentalOrderController(RentalService rentalService, NameIndexService nameIndexService) {
        this.rentalService = rentalService;
        this.nameIndexService = nameIndexService;
    }

    /** 下单 */
    @PostMapping
    public ApiResponse<RentalOrder> create(@Valid @RequestBody OrderCreateRequest request) {
        return ApiResponse.ok(rentalService.createOrder(request));
    }

    /** 交车：必须登记油量表读数与车损照片 */
    @PostMapping("/{id}/pickup")
    public ApiResponse<RentalOrder> pickup(@PathVariable Long id,
                                           @Valid @RequestBody HandoverRequest request) {
        return ApiResponse.ok(rentalService.pickup(id, request));
    }

    /** 还车并结算：必须登记油量表读数与车损照片 */
    @PostMapping("/{id}/return")
    public ApiResponse<RentalOrder> returnVehicle(@PathVariable Long id,
                                                  @Valid @RequestBody ReturnSettleRequest request) {
        return ApiResponse.ok(rentalService.returnAndSettle(id, request));
    }

    /** 归档 */
    @PostMapping("/{id}/archive")
    public ApiResponse<RentalOrder> archive(@PathVariable Long id) {
        return ApiResponse.ok(rentalService.archive(id));
    }

    /** 冲销（已结算/已归档记录的唯一撤销方式） */
    @PostMapping("/{id}/reverse")
    public ApiResponse<RentalOrder> reverse(@PathVariable Long id,
                                            @RequestBody(required = false) ReverseRequest request) {
        String reason = request != null ? request.getReason() : null;
        return ApiResponse.ok(rentalService.reverse(id, reason));
    }

    /**
     * 组合检索。customerName 支持姓名原文（中文/外文）、全拼、首字母，
     * 大小写与全半角容错，结果按相关度排序；该参数仅在拼音检索灰度开启时生效。
     */
    @GetMapping
    public ApiResponse<Page<RentalOrder>> search(
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime bizTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime bizTimeTo,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(rentalService.search(customerName, orderNo, status, bizTimeFrom, bizTimeTo,
                pageNum, pageSize));
    }

    /** 客户改名：订单姓名与拼音索引同事务更新并留 RENAME 事件 */
    @PutMapping("/{id}/customer")
    public ApiResponse<RentalOrder> renameCustomer(@PathVariable Long id,
                                                   @Valid @RequestBody RenameCustomerRequest request) {
        return ApiResponse.ok(rentalService.renameCustomer(id, request.getCustomerName()));
    }

    /**
     * 拼音索引全量重建（存量初始化/兜底自愈）。以订单为准逐单重算覆盖，需走写请求幂等头。
     */
    @PostMapping("/name-index/rebuild")
    public ApiResponse<Map<String, Integer>> rebuildNameIndex() {
        return ApiResponse.ok(Collections.singletonMap("rebuilt", nameIndexService.rebuildAll()));
    }

    /** 详情：订单 + 交接记录 + 事件流水 + 状态流转历史 */
    @GetMapping("/{id}")
    public ApiResponse<RentalOrderDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(rentalService.detail(id));
    }

    /**
     * 状态机合法路径视图：集中定义的合法流转（动作/起止状态/是否正向/备注）。
     * 现场照此操作，越级与回退均不在视图内。
     */
    @GetMapping("/state-machine")
    public ApiResponse<RentalStateMachineConfig> stateMachine() {
        return ApiResponse.ok(rentalService.stateMachineView());
    }

    /** 状态流转历史：每次流转一行，按发生顺序返回，可对到交接记录/操作人/业务时间/请求号。 */
    @GetMapping("/{id}/transitions")
    public ApiResponse<java.util.List<RentalOrderTransition>> transitions(@PathVariable Long id) {
        return ApiResponse.ok(rentalService.listTransitions(id));
    }

    /**
     * 通用状态流转：动作以状态机配置为准，新增状态/动作只改配置不改代码。
     * 交车/还车因强制登记油量与车损照片，仍走专属接口。
     */
    @PostMapping("/{id}/transitions")
    public ApiResponse<RentalOrder> transition(@PathVariable Long id,
                                               @Valid @RequestBody TransitionRequest request) {
        return ApiResponse.ok(rentalService.transition(id, request.getAction(), request.getRemark()));
    }

    /**
     * 租车单为全量记录，不允许物理删除；
     * 已结算/已归档的记录只许走 /reverse 冲销。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        throw new BizException("租车单为全量留痕记录，禁止物理删除；已结算/已归档请使用冲销接口");
    }
}
