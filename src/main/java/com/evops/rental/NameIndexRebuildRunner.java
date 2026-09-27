package com.evops.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动自愈：应用启动后以订单为准全量补齐/校正拼音索引。
 * 索引是可重建的派生数据，自愈失败不阻断启动（读路径默认经灰度闸关闭），仅记录错误日志。
 */
@Component
@Order(0)
public class NameIndexRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NameIndexRebuildRunner.class);

    private final NameIndexService nameIndexService;

    public NameIndexRebuildRunner(NameIndexService nameIndexService) {
        this.nameIndexService = nameIndexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int count = nameIndexService.rebuildAll();
            log.info("启动自愈：客户姓名拼音索引已按存量订单重建，共 {} 单", count);
        } catch (Exception e) {
            log.error("启动自愈：客户姓名拼音索引重建失败，新检索链路保持灰度关闭并请人工排查", e);
        }
    }
}
