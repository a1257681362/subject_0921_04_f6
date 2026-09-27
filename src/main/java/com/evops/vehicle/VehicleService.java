package com.evops.vehicle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VehicleService {

    private final VehicleMapper vehicleMapper;

    public VehicleService(VehicleMapper vehicleMapper) {
        this.vehicleMapper = vehicleMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Vehicle create(VehicleCreateRequest request) {
        Long duplicated = vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                .eq(Vehicle::getPlateNo, request.getPlateNo()));
        if (duplicated != null && duplicated > 0) {
            throw new BizException("车牌号已存在：" + request.getPlateNo());
        }
        Vehicle vehicle = new Vehicle();
        vehicle.setPlateNo(request.getPlateNo());
        vehicle.setModel(request.getModel());
        vehicle.setStatus("IDLE");
        vehicleMapper.insert(vehicle);
        return vehicle;
    }

    public Vehicle getById(Long id) {
        Vehicle vehicle = vehicleMapper.selectById(id);
        if (vehicle == null) {
            throw new BizException("车辆不存在：" + id);
        }
        return vehicle;
    }

    public List<Vehicle> list(String plateNo, String status, long pageNum, long pageSize) {
        LambdaQueryWrapper<Vehicle> wrapper = new LambdaQueryWrapper<Vehicle>()
                .eq(plateNo != null && !plateNo.trim().isEmpty(), Vehicle::getPlateNo, plateNo)
                .eq(status != null && !status.trim().isEmpty(), Vehicle::getStatus, status)
                .orderByDesc(Vehicle::getId);
        return vehicleMapper.selectPage(new Page<>(pageNum, pageSize), wrapper).getRecords();
    }
}
