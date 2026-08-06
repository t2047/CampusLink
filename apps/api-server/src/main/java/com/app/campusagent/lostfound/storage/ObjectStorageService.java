package com.app.campusagent.lostfound.storage;

import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {

    StoredObject upload(MultipartFile file);

    String createPresignedGetUrl(String objectKey);

    void delete(String objectKey);
}
