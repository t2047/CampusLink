package com.app.campusagent.lostfound.storage;

public record StoredObject(
        String objectKey,
        String originalName,
        String contentType,
        long size) {
}
