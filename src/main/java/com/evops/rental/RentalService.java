package com.evops.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.BizException;
import com.evops.rental.dto.HandoverRequest;
import com.evops.rental.dto.OrderCreateRequest;
import com.evops.rental.dto.ReturnSettleRequest;
import com.evops.rental.fsm.RentalOrderTransition;
import com.evops.rental.fsm.RentalOrderTransitionMapper;
import com.evops.rental.fsm.RentalStateMachine;
import com.evops.rental.fsm.RentalStateMachineConfig;
import com.evops.vehicle.Vehicle;
import com.evops.vehicle.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 租车单主流程。
 *
 * 交车生命周期为<b>配置驱动状态机</b>（见 {@link RentalStateMachineConfig}，合法路径集中在
 * application.yml）：
 * <pre>
 *   下单 CREATED --交车--> RENTING --还车结算--> SETTLED --归档--> ARCHIVED
 *   SETTLED / ARCHIVED --冲销--> REVERSED
 * </pre>
 * 越级、回退、未知动作都因“不在合法路径内”由状态机统一拒绝（409）；
 * 新增状态/动作只改配置，通用流转接口 /transitions 直接承载，不改代码。
 *
 * 规则：
 * - 每次状态推进走乐观锁 CAS，并行流转只有一个成功，败者整体回滚（见 {@link RentalStateMachine}）；
 * - 每次流转同事务写结构化流转历史 t_rental_order_transition，链完整可追溯；
 * - 每个业务动作的多表写入（订单、交接、事件、流转历史、车辆状态）共用一个事务，中途失败全部回退；
 * - 租车单全量留痕，不提供物理删除；
 * - 交车、还车都必须登记油量表读数与车损照片；
 * - 订单号库层唯一约束兜底重复提交（入口幂等拦截见 IdempotentInterceptor）。
 */
@Service
public class RentalService {

    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RentalOrderMapper orderMapper;
    private final RentalHandoverMapper handoverMapper;
    private final RentalOrderEventMapper eventMapper;
    private final RentalOrderTransitionMapper transitionMapper;
    private final VehicleMapper vehicleMapper;
    private final NameIndexService nameIndexService;
    private final NameSearchGrayGate nameSearchGrayGate;
    private final RentalStateMachine stateMachine;
    private final RentalStateMachineConfig fsmConfig;

    public RentalService(RentalOrderMapper orderMapper,
                         RentalHandoverMapper handoverMapper,
                         RentalOrderEventMapper eventMapper,
                         RentalOrderTransitionMapper transitionMapper,
                         VehicleMapper vehicleMapper,
                         NameIndexService nameIndexService,
                         NameSearchGrayGate nameSearchGrayGate,
                         RentalStateMachine stateMachine,
                         RentalStateMachineConfig fsmConfig) {
        this.orderMapper = orderMapper;
        this.handoverMapper = handoverMapper;
        this.eventMapper = eventMapper;
        this.transitionMapper = transitionMapper;
        this.vehicleMapper = vehicleMapper;
        this.nameIndexService = nameIndexService;
        this.nameSearchGrayGate = nameSearchGrayGate;
        this.stateMachine = stateMachine;
        this.fsmConfig = fsmConfig;
    }

    /** 下单：订单落状态机初始状态 + CREATE 事件，同一事务。 */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder createOrder(OrderCreateRequest request) {
        Vehicle vehicle = vehicleMapper.selectById(request.getVehicleId());
        if (vehicle == null) {
            throw new BizException("车辆不存在：" + request.getVehicleId());
        }
        if ("DISABLED".equals(vehicle.getStatus())) {
            throw new BizException("车辆已停用，不能下单：" + vehicle.getPlateNo());
        }

        RentalOrder order = new RentalOrder();
        order.setOrderNo(request.getOrderNo() != null && !request.getOrderNo().trim().isEmpty()
                ? request.getOrderNo().trim() : generateOrderNo());
        order.setVehicleId(vehicle.getId());
        order.setCustomerName(request.getCustomerName());
        order.setBranchCode(request.getBranchCode() == null || request.getBranchCode().trim().isEmpty()
                ? null : request.getBranchCode().trim());
        order.setStatus(fsmConfig.getInitialStatus());
        orderMapper.insert(order);

        recordEvent(order.getId(), EventType.CREATE,
                "下单，客户：" + order.getCustomerName());

        // 派生拼音索引与订单同事务写入，失败一起回滚
        nameIndexService.syncInTx(order.getId(), order.getCustomerName());
        return order;
    }

    /** 交车：登记油量/车损照片，状态机推进交车动作，车辆置 RENTING。 */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder pickup(Long orderId, HandoverRequest request) {
        RentalOrder order = requireOrder(orderId);
        // 越级/回退先由状态机拒绝（与车辆现场状态无关），再做车辆在库校验
        fsmConfig.requireRule(EventType.PICKUP, order.getStatus());
        Vehicle vehicle = requireVehicle(order.getVehicleId());
        if (!"IDLE".equals(vehicle.getStatus())) {
            throw new BizException("车辆不在库，无法交车，当前状态：" + vehicle.getStatus());
        }

        RentalHandover handover = insertHandover(orderId, "PICKUP", request);
        fireTransition(order, EventType.PICKUP, handover.getId(), null);

        vehicle.setStatus("RENTING");
        vehicleMapper.updateById(vehicle);

        recordEvent(orderId, EventType.PICKUP,
                "交车，油量表读数：" + request.getFuelReading());
        return orderMapper.selectById(orderId);
    }

    /** 还车并结算：登记油量/车损照片，状态机推进还车动作，车辆回 IDLE。 */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder returnAndSettle(Long orderId, ReturnSettleRequest request) {
        RentalOrder order = requireOrder(orderId);
        Vehicle vehicle = requireVehicle(order.getVehicleId());

        RentalHandover handover = insertHandover(orderId, "RETURN", request);

        order.setSettleAmount(request.getSettleAmount());
        fireTransition(order, EventType.RETURN, handover.getId(), null);

        if ("RENTING".equals(vehicle.getStatus())) {
            vehicle.setStatus("IDLE");
            vehicleMapper.updateById(vehicle);
        }

        recordEvent(orderId, EventType.RETURN,
                "还车结算，油量表读数：" + request.getFuelReading()
                        + (request.getSettleAmount() != null
                        ? "，结算金额：" + request.getSettleAmount().toPlainString() : ""));
        return orderMapper.selectById(orderId);
    }

    /** 归档：状态机推进归档动作。 */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder archive(Long orderId) {
        RentalOrder order = requireOrder(orderId);
        fireTransition(order, EventType.ARCHIVE, null, null);
        recordEvent(orderId, EventType.ARCHIVE, "订单归档");
        return orderMapper.selectById(orderId);
    }

    /**
     * 冲销：已结算/已归档的唯一撤销方式，合法起始状态由状态机配置集中定义。
     * 冲销后确保车辆已回库。
     */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder reverse(Long orderId, String reason) {
        RentalOrder order = requireOrder(orderId);
        String trimmedReason = reason == null ? null : reason.trim();
        fireTransition(order, EventType.REVERSE, null, trimmedReason);

        Vehicle vehicle = vehicleMapper.selectById(order.getVehicleId());
        if (vehicle != null && "RENTING".equals(vehicle.getStatus())) {
            vehicle.setStatus("IDLE");
            vehicleMapper.updateById(vehicle);
        }

        recordEvent(orderId, EventType.REVERSE,
                "订单冲销" + (trimmedReason != null && !trimmedReason.isEmpty()
                        ? "，原因：" + trimmedReason : ""));
        return orderMapper.selectById(orderId);
    }

    /**
     * 通用状态流转：动作与起止状态全部取自状态机配置。
     * 新增状态/动作后无需改代码、无需发版，现场直接按配置的 action 调用。
     * 带交接的专属动作（交车/还车）仍走各自接口以强制油量/照片登记。
     */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder transition(Long orderId, String action, String remark) {
        RentalOrder order = requireOrder(orderId);
        String act = action == null ? null : action.trim().toUpperCase();
        if (act == null || act.isEmpty()) {
            throw new BizException("流转动作不能为空");
        }
        if (EventType.PICKUP.equals(act) || EventType.RETURN.equals(act)) {
            throw new BizException(act + " 必须登记油量表读数与车损照片，请使用交车/还车专属接口");
        }
        String trimmedRemark = remark == null ? null : remark.trim();
        RentalOrderTransition transition = fireTransition(order, act, null, trimmedRemark);
        recordEvent(orderId, act, "状态流转：" + transition.getFromStatus() + " -> "
                + transition.getToStatus()
                + (trimmedRemark != null && !trimmedRemark.isEmpty() ? "，备注：" + trimmedRemark : ""));
        return orderMapper.selectById(orderId);
    }

    /**
     * 客户改名：改订单姓名 + 同步拼音索引 + RENAME 事件，同一事务，中途失败全部回滚。
     * 任何状态的订单都允许改名（仅订正客户姓名，不触碰状态机与留痕）。
     */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder renameCustomer(Long orderId, String newName) {
        String target = newName == null ? null : newName.trim();
        if (target == null || target.isEmpty()) {
            throw new BizException("客户姓名不能为空");
        }
        RentalOrder order = requireOrder(orderId);
        String oldName = order.getCustomerName();
        if (target.equals(oldName)) {
            return order;
        }

        order.setCustomerName(target);
        orderMapper.updateById(order);
        // 索引随改名同事务同步，保证“改名后索引同步”
        nameIndexService.syncInTx(orderId, target);
        recordEvent(orderId, EventType.RENAME,
                "客户改名：" + oldName + " -> " + target);
        return orderMapper.selectById(orderId);
    }

    /**
     * 按订单号与业务时间组合检索（均可单独或组合使用）。
     * customerName 仅在拼音检索灰度开启时生效（全拼/首字母/原文，大小写与全半角容错），
     * 灰度关闭时该参数被忽略，检索行为与升级前完全一致，便于值守当天即时回退。
     */
    public Page<RentalOrder> search(String customerName, String orderNo, String status,
                                    LocalDateTime bizTimeFrom, LocalDateTime bizTimeTo,
                                    long pageNum, long pageSize) {
        String trimmedOrderNo = orderNo == null ? null : orderNo.trim();
        String trimmedStatus = status == null ? null : status.trim();
        if (trimmedOrderNo != null && trimmedOrderNo.isEmpty()) {
            trimmedOrderNo = null;
        }
        if (trimmedStatus != null && trimmedStatus.isEmpty()) {
            trimmedStatus = null;
        }
        String keyword = customerName == null ? null : PinyinNames.normalize(customerName);

        if (keyword != null && !keyword.isEmpty() && nameSearchGrayGate.isOpen()) {
            Page<RentalOrder> page = new Page<>(pageNum, pageSize);
            orderMapper.searchByName(page, keyword, trimmedOrderNo, trimmedStatus, bizTimeFrom, bizTimeTo);
            return page;
        }

        LambdaQueryWrapper<RentalOrder> wrapper = new LambdaQueryWrapper<RentalOrder>()
                .like(trimmedOrderNo != null && !trimmedOrderNo.isEmpty(),
                        RentalOrder::getOrderNo, trimmedOrderNo)
                .eq(trimmedStatus != null && !trimmedStatus.isEmpty(),
                        RentalOrder::getStatus, trimmedStatus)
                .ge(bizTimeFrom != null, RentalOrder::getBizTime, bizTimeFrom)
                .le(bizTimeTo != null, RentalOrder::getBizTime, bizTimeTo)
                .orderByDesc(RentalOrder::getBizTime)
                .orderByDesc(RentalOrder::getId);
        return orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    public RentalOrderDetail detail(Long orderId) {
        RentalOrder order = requireOrder(orderId);
        List<RentalHandover> handovers = handoverMapper.selectList(
                new LambdaQueryWrapper<RentalHandover>()
                        .eq(RentalHandover::getOrderId, orderId)
                        .orderByAsc(RentalHandover::getId));
        List<RentalOrderEvent> events = eventMapper.selectList(
                new LambdaQueryWrapper<RentalOrderEvent>()
                        .eq(RentalOrderEvent::getOrderId, orderId)
                        .orderByAsc(RentalOrderEvent::getId));
        List<RentalOrderTransition> transitions = listTransitions(orderId);
        return new RentalOrderDetail(order, handovers, events, transitions);
    }

    /** 流转历史：按发生顺序返回完整状态链（每次流转一行，可追溯到交接/操作人/请求号）。 */
    public List<RentalOrderTransition> listTransitions(Long orderId) {
        requireOrder(orderId);
        return transitionMapper.selectList(
                new LambdaQueryWrapper<RentalOrderTransition>()
                        .eq(RentalOrderTransition::getOrderId, orderId)
                        .orderByAsc(RentalOrderTransition::getId));
    }

    /** 状态机合法路径视图（集中定义的配置原样可读）。 */
    public RentalStateMachineConfig stateMachineView() {
        return fsmConfig;
    }

    // ---- 内部辅助 ----

    /** 推进状态机：合法路径判定 + CAS 更新 + 流转历史，同一事务。 */
    private RentalOrderTransition fireTransition(RentalOrder order, String action,
                                                 Long handoverId, String remark) {
        return stateMachine.fire(order, action, handoverId, remark);
    }

    private RentalOrder requireOrder(Long orderId) {
        RentalOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException("租车单不存在：" + orderId);
        }
        return order;
    }

    private Vehicle requireVehicle(Long vehicleId) {
        Vehicle vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw new BizException("车辆不存在：" + vehicleId);
        }
        return vehicle;
    }

    private RentalHandover insertHandover(Long orderId, String type, HandoverRequest request) {
        RentalHandover handover = new RentalHandover();
        handover.setOrderId(orderId);
        handover.setType(type);
        handover.setFuelReading(request.getFuelReading());
        handover.setDamagePhotos(request.getDamagePhotos());
        handover.setRemark(request.getRemark());
        handoverMapper.insert(handover);
        return handover;
    }

    private void recordEvent(Long orderId, String eventType, String detail) {
        RentalOrderEvent event = new RentalOrderEvent();
        event.setOrderId(orderId);
        event.setEventType(eventType);
        event.setDetail(detail);
        eventMapper.insert(event);
    }

    private String generateOrderNo() {
        return "RO" + LocalDateTime.now().format(ORDER_NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /** 订单状态常量（默认配置中的状态名；新增状态以 application.yml 配置为准） */
    public static final class OrderStatus {
        public static final String CREATED = "CREATED";
        public static final String RENTING = "RENTING";
        public static final String SETTLED = "SETTLED";
        public static final String ARCHIVED = "ARCHIVED";
        public static final String REVERSED = "REVERSED";

        private OrderStatus() {}
    }

    /** 事件类型常量（流转事件动作名与状态机配置一致；RENAME 为非状态业务动作） */
    public static final class EventType {
        public static final String CREATE = "CREATE";
        public static final String PICKUP = "PICKUP";
        public static final String RETURN = "RETURN";
        public static final String ARCHIVE = "ARCHIVE";
        public static final String REVERSE = "REVERSE";
        public static final String RENAME = "RENAME";

        private EventType() {}
    }
}
