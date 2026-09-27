package com.evops.rental;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 客户姓名拼音索引 CRUD。order_id 即主键，与租车单一一对应。 */
@Mapper
public interface RentalOrderNameIndexMapper extends BaseMapper<RentalOrderNameIndex> {
}
