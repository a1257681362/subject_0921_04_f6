package com.evops.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 客户姓名拼音索引同步。
 *
 * - 下单、改名在各自业务事务内调用 {@link #syncInTx}，索引与订单同生共改，中途失败一起回滚；
 * - {@link #rebuildAll} 全量按订单重算覆盖，供存量初始化、手动重建与启动自愈；
 * - 派生列（normalized/full/initials）全部由 {@link PinyinNames} 同一套规则生成。
 */
@Service
public class NameIndexService {

    private static final Logger log = LoggerFactory.getLogger(NameIndexService.class);

    private final RentalOrderMapper orderMapper;
    private final RentalOrderNameIndexMapper indexMapper;

    public NameIndexService(RentalOrderMapper orderMapper, RentalOrderNameIndexMapper indexMapper) {
        this.orderMapper = orderMapper;
        this.indexMapper = indexMapper;
    }

    /**
     * 同步单个订单的姓名索引（upsert）。须在调用方业务事务内执行（传播 REQUIRED），
     * 保证与订单写入同提交同回滚。
     */
    public void syncInTx(Long orderId, String customerName) {
        RentalOrderNameIndex index = build(orderId, customerName);
        // 已存在则覆盖派生列（新对象 update_time 为空，会由填充器刷新）；不存在再插入。
        int updated = indexMapper.updateById(index);
        if (updated == 0) {
            indexMapper.insert(index);
        }
    }

    /**
     * 全量重建：以订单为准逐单重算，补缺行、覆盖旧派生值（如改名漏同步、拼音算法升级）。
     * 返回处理的订单条数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int rebuildAll() {
        List<RentalOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<>());
        for (RentalOrder order : orders) {
            syncInTx(order.getId(), order.getCustomerName());
        }
        log.info("客户姓名拼音索引全量重建完成，订单数：{}", orders.size());
        return orders.size();
    }

    private RentalOrderNameIndex build(Long orderId, String customerName) {
        RentalOrderNameIndex index = new RentalOrderNameIndex();
        index.setOrderId(orderId);
        index.setCustomerName(customerName);
        index.setNormalizedName(PinyinNames.normalize(customerName));
        index.setFullPinyin(PinyinNames.fullPinyin(customerName));
        index.setInitials(PinyinNames.initials(customerName));
        return index;
    }
}
