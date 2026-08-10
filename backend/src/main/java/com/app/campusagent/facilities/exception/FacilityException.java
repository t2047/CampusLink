package com.app.campusagent.facilities.exception;

import org.springframework.http.HttpStatus;

public class FacilityException extends RuntimeException {

    private final FacilityErrorCode code;
    private final HttpStatus status;

    public FacilityException(FacilityErrorCode code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public FacilityErrorCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
