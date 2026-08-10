package com.app.campusagent.facilities.security;

/** Authentication failure raised for an invalid Facilities delegation token. */
public class DelegationTokenVerificationException extends RuntimeException {

    public DelegationTokenVerificationException(String message) {
        super(message);
    }
}
