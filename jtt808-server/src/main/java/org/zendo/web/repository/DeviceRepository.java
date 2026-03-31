package org.zendo.web.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.zendo.web.model.entity.Device;

import java.util.Optional;

@Repository
public interface DeviceRepository extends MongoRepository<Device, String> {

    Optional<Device> findByMobileNo(String mobileNo);

    Optional<Device> findByDeviceId(String deviceId);
}
