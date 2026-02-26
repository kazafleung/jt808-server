package org.yzh.web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yzh.web.model.entity.Device;
import org.yzh.web.repository.DeviceRepository;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;

    /**
     * Upsert device on T0100 registration.
     * If the device (by mobileNo) already exists, update its info.
     * Returns the saved document (with MongoDB _id populated).
     */
    public Device saveOrUpdate(Device device) {
        return deviceRepository.findByMobileNo(device.getMobileNo())
                .map(existing -> {
                    // update mutable fields, preserve registeredAt
                    existing.setDeviceId(device.getDeviceId());
                    existing.setPlateNo(device.getPlateNo());
                    existing.setProtocolVersion(device.getProtocolVersion());
                    return deviceRepository.save(existing);
                })
                .orElseGet(() -> {
                    device.setRegisteredAt(LocalDateTime.now());
                    Device saved = deviceRepository.save(device);
                    log.info("New device registered: mobileNo={}, deviceId={}", saved.getMobileNo(), saved.getDeviceId());
                    return saved;
                });
    }
}
