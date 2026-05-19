package org.zendo.web.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.zendo.web.model.entity.DeviceFileRequest;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceFileRequestRepository extends MongoRepository<DeviceFileRequest, String> {

    List<DeviceFileRequest> findByCidAndStatus(String cid, DeviceFileRequest.Status status);

    List<DeviceFileRequest> findByStatus(DeviceFileRequest.Status status);

    Optional<DeviceFileRequest> findByCidAndSerialNo(String cid, String serialNo);
}
