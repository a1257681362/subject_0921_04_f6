package com.evops.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.BizException;
import com.evops.rental.dto.HandoverRequest;
import com.evops.rental.dto.OrderCreateRequest;
import com.evops.rental.dto.ReturnSettleRequest;
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
 * 状态机：
 *   下单 CREATED --交车--> RENTING --还车结算--> SETTLED --归档--> ARCHIVED
 *   SETTLED / ARCHIVED --冲销--> REVERSED
 *
 * 规则：
 * - 每个业务动作的多表写入（订单、交接、事件、车辆状态）共用一个事务，中途失败全部回退；
 * - 租车单全量留痕，不提供物理删除；已结算/已归档只允许冲销；
 * - 交车、还车都必须登记油量表读数与车损照片；
 * - 订单号库层唯一约束兜底重复提交（入口幂等拦截见 IdempotentInterceptor）。
 */
@Service
public class RentalService {

    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RentalOrderMapper orderMapper;
    private final RentalHandoverMapper handoverMapper;
    private final RentalOrderEventMapper eventMapper;
    private final VehicleMapper vehicleMapper;
    private final NameIndexService nameIndexService;
    private final NameSearchGrayGate nameSearchGrayGate;

    public RentalService(RentalOrderMapper orderMapper,
                         RentalHandoverMapper handoverMapper,
                         RentalOrderEventMapper eventMapper,
                         VehicleMapper vehicleMapper,
                         NameIndexService nameIndexService,
                         NameSearchGrayGate nameSearchGrayGate) {
        this.orderMapper = orderMapper;
        this.handoverMapper = handoverMapper;
        this.eventMapper = eventMapper;
        this.vehicleMapper = vehicleMapper;
        this.nameIndexService = nameIndexService;
        this.nameSearchGrayGate = nameSearchGrayGate;
    }

    /** 下单：订单 CREATED + CREATE 事件，同一事务。 */
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
        order.setStatus(OrderStatus.CREATED);
        orderMapper.insert(order);

        recordEvent(order.getId(), EventType.CREATE,
                "下单，客户：" + order.getCustomerName());

        // 派生拼音索引与订单同事务写入，失败一起回滚
        nameIndexService.syncInTx(order.getId(), order.getCustomerName());
        return order;
    }

    /** 交车：登记油量/车损照片，订单 CREATED -> RENTING，车辆置 RENTING。 */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder pickup(Long orderId, HandoverRequest request) {
        RentalOrder order = requireOrder(orderId);
        if (!OrderStatus.CREATED.equals(order.getStatus())) {
            throw new BizException("只有已下单(CREATED)的订单可以交车，当前状态：" + order.getStatus());
        }
        Vehicle vehicle = requireVehicle(order.getVehicleId());
        if (!"IDLE".equals(vehicle.getStatus())) {
            throw new BizException("车辆不在库，无法交车，当前状态：" + vehicle.getStatus());
        }

        insertHandover(orderId, "PICKUP", request);

        order.setStatus(OrderStatus.RENTING);
        orderMapper.updateById(order);
        vehicle.setStatus("RENTING");
        vehicleMapper.updateById(vehicle);

        recordEvent(orderId, EventType.PICKUP,
                "交车，油量表读数：" + request.getFuelReading());
        return orderMapper.selectById(orderId);
    }

    /** 还车并结算：登记油量/车损照片，订单 RENTING -> SETTLED，车辆回 IDLE。 */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder returnAndSettle(Long orderId, ReturnSettleRequest request) {
        RentalOrder order = requireOrder(orderId);
        if (!OrderStatus.RENTING.equals(order.getStatus())) {
            throw new BizException("只有租用中(RENTING)的订单可以还车，当前状态：" + order.getStatus());
        }
        Vehicle vehicle = requireVehicle(order.getVehicleId());

        insertHandover(orderId, "RETURN", request);

        order.setStatus(OrderStatus.SETTLED);
        order.setSettleAmount(request.getSettleAmount());
        orderMapper.updateById(order);

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

    /** 归档：SETTLED -> ARCHIVED。 */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder archive(Long orderId) {
        RentalOrder order = requireOrder(orderId);
        if (!OrderStatus.SETTLED.equals(order.getStatus())) {
            throw new BizException("只有已结算(SETTLED)的订单可以归档，当前状态：" + order.getStatus());
        }
        order.setStatus(OrderStatus.ARCHIVED);
        orderMapper.updateById(order);
        recordEvent(orderId, EventType.ARCHIVE, "订单归档");
        return orderMapper.selectById(orderId);
    }

    /**
     * 冲销：已结算/已归档只许冲销、不许物理删除。
     * SETTLED / ARCHIVED -> REVERSED，并确保车辆已回库。
     */
    @Transactional(rollbackFor = Exception.class)
    public RentalOrder reverse(Long orderId, String reason) {
        RentalOrder order = requireOrder(orderId);
        if (!OrderStatus.SETTLED.equals(order.getStatus())
                && !OrderStatus.ARCHIVED.equals(order.getStatus())) {
            throw new BizException("只有已结算(SETTLED)或已归档(ARCHIVED)的记录允许冲销，当前状态："
                    + order.getStatus());
        }
        Vehicle vehicle = vehicleMapper.selectById(order.getVehicleId());
        if (vehicle != null && "RENTING".equals(vehicle.getStatus())) {
            vehicle.setStatus("IDLE");
            vehicleMapper.updateById(vehicle);
        }

        order.setStatus(OrderStatus.REVERSED);
        orderMapper.updateById(order);
        recordEvent(orderId, EventType.REVERSE,
                "订单冲销" + (reason != null && !reason.trim().isEmpty() ? "，原因：" + reason.trim() : ""));
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
        return new RentalOrderDetail(order, handovers, events);
    }

    // ---- 内部辅助 ----

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

    private void insertHandover(Long orderId, String type, HandoverRequest request) {
        RentalHandover handover = new RentalHandover();
        handover.setOrderId(orderId);
        handover.setType(type);
        handover.setFuelReading(request.getFuelReading());
        handover.setDamagePhotos(request.getDamagePhotos());
        handover.setRemark(request.getRemark());
        handoverMapper.insert(handover);
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

    /** 订单状态常量 */
    public static final class OrderStatus {
        public static final String CREATED = "CREATED";
        public static final String RENTING = "RENTING";
        public static final String SETTLED = "SETTLED";
        public static final String ARCHIVED = "ARCHIVED";
        public static final String REVERSED = "REVERSED";

        private OrderStatus() {}
    }

    /** 事件类型常量 */
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
