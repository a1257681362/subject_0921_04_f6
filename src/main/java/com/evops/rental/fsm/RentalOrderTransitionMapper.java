package com.evops.rental.fsm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RentalOrderTransitionMapper extends BaseMapper<RentalOrderTransition> {
}
