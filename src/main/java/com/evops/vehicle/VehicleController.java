package com.evops.vehicle;

import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    /** 车辆入账。写请求需带头 X-Operator、X-Request-No。 */
    @PostMapping
    public ApiResponse<Vehicle> create(@Valid @RequestBody VehicleCreateRequest request) {
        return ApiResponse.ok(vehicleService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<Vehicle> get(@PathVariable Long id) {
        return ApiResponse.ok(vehicleService.getById(id));
    }

    @GetMapping
    public ApiResponse<List<Vehicle>> list(
            @RequestParam(required = false) String plateNo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(vehicleService.list(plateNo, status, pageNum, pageSize));
    }
}
