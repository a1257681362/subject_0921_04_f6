package com.evops.telematics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.rental.RentalHandover;
import com.evops.rental.RentalHandoverMapper;
import com.evops.rental.RentalOrder;
import com.evops.rental.RentalOrderMapper;
import com.evops.vehicle.Vehicle;
import com.evops.vehicle.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 车载定位平台文件往来。
 *
 * 导出（我方 -> 平台）：按业务日抽取交接记录（PICKUP/RETURN）联车辆表生成 CSV，
 * 文件按业务日分目录归档落盘并留归档记录；一业务日可多次导出（补发），每次生成新文件。
 *
 * 导入（平台 -> 我方）：设备回执 CSV，全或无——任一行非法整单 FAILED 且业务行零写入，
 * 错误明细带行号。幂等三层：入口请求号（既有拦截器）、文件哈希（同文件重复导入返回首次
 * 结果）、业务键 (plate_no, device_no)（不同文件重复上报按最新覆盖）。
 */
@Service
public class TelematicsService {

    /** 导出列（定序，与对方规范一致） */
    public static final List<String> EXPORT_HEADER = Arrays.asList(
            "order_no", "plate_no", "handover_type", "handover_time",
            "fuel_reading", "damage_photos", "remark");

    /** 导入列（定序，模板即按此生成） */
    public static final List<String> IMPORT_HEADER = Arrays.asList(
            "plate_no", "device_no", "device_status", "report_time", "remark");

    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter DAY_DIR_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");

    private static final Set<String> DEVICE_STATUSES = new HashSet<>(Arrays.asList("ONLINE", "OFFLINE"));

    /** 单文件校验错误最多记录条数，超出截断防止错误明细爆炸 */
    private static final int MAX_ERRORS = 100;

    private final TelematicsProperties properties;
    private final TelematicsExportFileMapper exportMapper;
    private final TelematicsImportFileMapper importMapper;
    private final TelematicsDeviceReportMapper deviceMapper;
    private final RentalHandoverMapper handoverMapper;
    private final RentalOrderMapper orderMapper;
    private final VehicleMapper vehicleMapper;

    public TelematicsService(TelematicsProperties properties,
                             TelematicsExportFileMapper exportMapper,
                             TelematicsImportFileMapper importMapper,
                             TelematicsDeviceReportMapper deviceMapper,
                             RentalHandoverMapper handoverMapper,
                             RentalOrderMapper orderMapper,
                             VehicleMapper vehicleMapper) {
        this.properties = properties;
        this.exportMapper = exportMapper;
        this.importMapper = importMapper;
        this.deviceMapper = deviceMapper;
        this.handoverMapper = handoverMapper;
        this.orderMapper = orderMapper;
        this.vehicleMapper = vehicleMapper;
    }

    // =====================================================================
    // 导出
    // =====================================================================

    /**
     * 导出指定业务日的交接记录为 CSV 并归档。文件落盘与归档记录同一事务；
     * 落库失败时尽力删除已写文件，不留无记录的孤儿文件。
     */
    @Transactional(rollbackFor = Exception.class)
    public TelematicsExportFile exportHandovers(LocalDate bizDate) {
        LocalDateTime from = bizDate.atStartOfDay();
        LocalDateTime to = bizDate.plusDays(1).atStartOfDay();
        List<RentalHandover> handovers = handoverMapper.selectList(
                new LambdaQueryWrapper<RentalHandover>()
                        .ge(RentalHandover::getBizTime, from)
                        .lt(RentalHandover::getBizTime, to)
                        .orderByAsc(RentalHandover::getBizTime)
                        .orderByAsc(RentalHandover::getId));

        Charset charset = properties.charsetObj();
        StringBuilder sb = new StringBuilder();
        sb.append(CsvCodec.encodeRecord(EXPORT_HEADER)).append(CsvCodec.LINE_SEP);
        for (RentalHandover handover : handovers) {
            RentalOrder order = orderMapper.selectById(handover.getOrderId());
            Vehicle vehicle = order != null ? vehicleMapper.selectById(order.getVehicleId()) : null;
            sb.append(CsvCodec.encodeRecord(Arrays.asList(
                    order != null ? order.getOrderNo() : null,
                    vehicle != null ? vehicle.getPlateNo() : null,
                    handover.getType(),
                    handover.getBizTime() != null ? TIME_FMT.format(handover.getBizTime()) : null,
                    handover.getFuelReading() != null ? String.valueOf(handover.getFuelReading()) : null,
                    handover.getDamagePhotos(),
                    handover.getRemark()
            ))).append(CsvCodec.LINE_SEP);
        }

        String dayDir = bizDate.format(DAY_DIR_FMT);
        String fileName = "handovers_" + dayDir + "_" + LocalDateTime.now().format(FILE_TIME_FMT)
                + "_" + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)) + ".csv";
        Path path = Paths.get(properties.getExportDir(), dayDir, fileName);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, sb.toString().getBytes(charset));
        } catch (IOException ex) {
            throw new BizException("导出文件写入失败：" + ex.getMessage());
        }

        TelematicsExportFile record = new TelematicsExportFile();
        record.setBizDate(bizDate);
        record.setFileName(fileName);
        record.setFilePath(path.toString());
        record.setRecordCount(handovers.size());
        record.setCharset(charset.name());
        try {
            exportMapper.insert(record);
        } catch (RuntimeException ex) {
            // 落库失败（事务回滚）：清理已写文件，避免无记录孤儿文件
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 尽力清理，清理失败由运维按归档目录巡检
            }
            throw ex;
        }
        return record;
    }

    public List<TelematicsExportFile> listExports(LocalDate bizDate) {
        return exportMapper.selectList(new LambdaQueryWrapper<TelematicsExportFile>()
                .eq(bizDate != null, TelematicsExportFile::getBizDate, bizDate)
                .orderByDesc(TelematicsExportFile::getId));
    }

    /** 读取导出文件内容供下载。 */
    public byte[] readExportFile(Long id) {
        TelematicsExportFile record = requireExport(id);
        try {
            return Files.readAllBytes(Paths.get(record.getFilePath()));
        } catch (IOException ex) {
            throw new BizException("导出文件读取失败（可能已被清理）：" + record.getFilePath());
        }
    }

    public TelematicsExportFile requireExport(Long id) {
        TelematicsExportFile record = exportMapper.selectById(id);
        if (record == null) {
            throw new BizException("导出记录不存在：" + id);
        }
        return record;
    }

    // =====================================================================
    // 导入
    // =====================================================================

    /** 导入模板：表头 + 一行示例（示例行导入前请删除）。 */
    public byte[] templateBytes() {
        String content = CsvCodec.encodeRecord(IMPORT_HEADER) + CsvCodec.LINE_SEP
                + CsvCodec.encodeRecord(Arrays.asList(
                        "京A12345", "DEV-0001", "ONLINE", "2026-09-26 08:30:00", "示例行，导入前请删除"))
                + CsvCodec.LINE_SEP;
        return content.getBytes(properties.charsetObj());
    }

    /**
     * 导入设备回执文件。同一文件（内容哈希相同）重复导入直接返回首次结果（duplicate=true）；
     * 全或无：任一行非法整单 FAILED、业务行零写入，错误明细带行号。
     */
    @Transactional(rollbackFor = Exception.class)
    public TelematicsImportFile importReport(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BizException("上传文件为空");
        }
        String hash = sha256(content);
        TelematicsImportFile existing = importMapper.selectOne(new LambdaQueryWrapper<TelematicsImportFile>()
                .eq(TelematicsImportFile::getFileHash, hash));
        if (existing != null) {
            existing.setDuplicate(true);
            return existing;
        }

        Charset charset = properties.charsetObj();
        TelematicsImportFile record = new TelematicsImportFile();
        record.setFileName(fileName != null && !fileName.trim().isEmpty() ? fileName.trim() : "unknown.csv");
        record.setFileHash(hash);
        record.setCharset(charset.name());

        List<CsvCodec.Row> rows;
        try {
            rows = CsvCodec.parse(new String(content, charset));
        } catch (CsvFormatException ex) {
            record.setStatus(TelematicsImportFile.STATUS_FAILED);
            record.setErrorDetail("第 " + ex.getLineNo() + " 行：" + ex.getMessage());
            importMapper.insert(record);
            return record;
        }

        if (rows.isEmpty() || !IMPORT_HEADER.equals(rows.get(0).getFields())) {
            record.setStatus(TelematicsImportFile.STATUS_FAILED);
            record.setErrorDetail("第 1 行：表头与模板不一致，应为 " + String.join(",", IMPORT_HEADER));
            importMapper.insert(record);
            return record;
        }

        List<String> errors = new ArrayList<>();
        List<DeviceRow> devices = new ArrayList<>();
        Map<String, Integer> seenInFile = new HashMap<>();
        for (int r = 1; r < rows.size(); r++) {
            CsvCodec.Row row = rows.get(r);
            DeviceRow device = validateRow(row, errors, seenInFile);
            if (device != null) {
                devices.add(device);
            }
            if (errors.size() >= MAX_ERRORS) {
                errors.add("错误过多，仅显示前 " + MAX_ERRORS + " 条");
                break;
            }
        }
        record.setTotalLines(rows.size() - 1);

        if (!errors.isEmpty()) {
            record.setStatus(TelematicsImportFile.STATUS_FAILED);
            record.setSuccessLines(0);
            record.setErrorDetail(String.join("\n", errors));
            importMapper.insert(record);
            return record;
        }

        // 先落导入记录拿到 id，回执行再挂 import_file_id（同一事务，失败一起回滚）
        record.setStatus(TelematicsImportFile.STATUS_SUCCESS);
        record.setSuccessLines(devices.size());
        importMapper.insert(record);
        for (DeviceRow device : devices) {
            upsertDevice(record, device);
        }
        return record;
    }

    /** 单行校验：合法返回 DeviceRow，非法登记错误（带行号）返回 null。 */
    private DeviceRow validateRow(CsvCodec.Row row, List<String> errors, Map<String, Integer> seenInFile) {
        int lineNo = row.getLineNo();
        List<String> f = row.getFields();
        if (f.size() != IMPORT_HEADER.size()) {
            errors.add("第 " + lineNo + " 行：列数应为 " + IMPORT_HEADER.size() + "，实际 " + f.size());
            return null;
        }
        String plateNo = trim(f.get(0));
        String deviceNo = trim(f.get(1));
        String deviceStatus = trim(f.get(2));
        String reportTimeRaw = trim(f.get(3));
        String remark = f.get(4);   // 可空列保留 null/空串原样

        String error = null;
        if (plateNo == null || plateNo.isEmpty()) {
            error = "plate_no 不能为空";
        } else if (plateNo.length() > 32) {
            error = "plate_no 超长（最大 32）";
        } else if (deviceNo == null || deviceNo.isEmpty()) {
            error = "device_no 不能为空";
        } else if (deviceNo.length() > 64) {
            error = "device_no 超长（最大 64）";
        } else if (deviceStatus == null || !DEVICE_STATUSES.contains(deviceStatus)) {
            error = "device_status 非法：" + deviceStatus + "（仅支持 ONLINE/OFFLINE）";
        } else if (reportTimeRaw == null || reportTimeRaw.isEmpty()) {
            error = "report_time 不能为空";
        } else if (remark != null && remark.length() > 500) {
            error = "remark 超长（最大 500）";
        }
        if (error != null) {
            errors.add("第 " + lineNo + " 行：" + error);
            return null;
        }

        LocalDateTime reportTime;
        try {
            reportTime = LocalDateTime.parse(reportTimeRaw, TIME_FMT);
        } catch (DateTimeParseException ex) {
            errors.add("第 " + lineNo + " 行：report_time 格式应为 yyyy-MM-dd HH:mm:ss：" + reportTimeRaw);
            return null;
        }

        String key = plateNo + "|" + deviceNo;
        Integer firstLine = seenInFile.putIfAbsent(key, lineNo);
        if (firstLine != null) {
            errors.add("第 " + lineNo + " 行：与第 " + firstLine + " 行重复（plate_no + device_no 相同）");
            return null;
        }

        DeviceRow device = new DeviceRow();
        device.plateNo = plateNo;
        device.deviceNo = deviceNo;
        device.deviceStatus = deviceStatus;
        device.reportTime = reportTime;
        device.remark = remark;
        return device;
    }

    /** 业务键 (plate_no, device_no) 幂等：已存在按最新内容覆盖。 */
    private void upsertDevice(TelematicsImportFile record, DeviceRow device) {
        TelematicsDeviceReport existing = deviceMapper.selectOne(new LambdaQueryWrapper<TelematicsDeviceReport>()
                .eq(TelematicsDeviceReport::getPlateNo, device.plateNo)
                .eq(TelematicsDeviceReport::getDeviceNo, device.deviceNo));
        if (existing == null) {
            TelematicsDeviceReport report = new TelematicsDeviceReport();
            report.setPlateNo(device.plateNo);
            report.setDeviceNo(device.deviceNo);
            report.setDeviceStatus(device.deviceStatus);
            report.setReportTime(device.reportTime);
            report.setRemark(device.remark);
            report.setImportFileId(record.getId());
            deviceMapper.insert(report);
        } else {
            existing.setDeviceStatus(device.deviceStatus);
            existing.setReportTime(device.reportTime);
            existing.setRemark(device.remark);
            existing.setImportFileId(record.getId());
            deviceMapper.updateById(existing);
        }
    }

    public List<TelematicsImportFile> listImports() {
        return importMapper.selectList(new LambdaQueryWrapper<TelematicsImportFile>()
                .orderByDesc(TelematicsImportFile::getId));
    }

    public TelematicsImportFile requireImport(Long id) {
        TelematicsImportFile record = importMapper.selectById(id);
        if (record == null) {
            throw new BizException("导入记录不存在：" + id);
        }
        return record;
    }

    public List<TelematicsDeviceReport> listDeviceReports(String plateNo) {
        String trimmed = plateNo == null ? null : plateNo.trim();
        return deviceMapper.selectList(new LambdaQueryWrapper<TelematicsDeviceReport>()
                .eq(trimmed != null && !trimmed.isEmpty(), TelematicsDeviceReport::getPlateNo, trimmed)
                .orderByAsc(TelematicsDeviceReport::getPlateNo)
                .orderByAsc(TelematicsDeviceReport::getDeviceNo));
    }

    // ---- 内部辅助 ----

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 校验通过的一行设备回执。 */
    private static final class DeviceRow {
        private String plateNo;
        private String deviceNo;
        private String deviceStatus;
        private LocalDateTime reportTime;
        private String remark;
    }
}
