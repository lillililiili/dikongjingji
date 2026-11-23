package com.lowaltitude.inspection.identity.domain;

/**
 * Whether a login (a refresh-token family) has a signed-in device behind it (S5-8). ACTIVE: the
 * device is still signed in; ENDED: it was signed out (from 登录设备, a phone change or closure);
 * UNKNOWN: the login started before devices were recorded, or there is no database (tests), and
 * renewing it goes on as before.
 */
public enum LoginSessionStatus {
    ACTIVE,
    ENDED,
    UNKNOWN
}
