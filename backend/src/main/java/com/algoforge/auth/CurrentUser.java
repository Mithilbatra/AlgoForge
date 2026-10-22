package com.algoforge.auth;

import org.springframework.security.core.Authentication;

/** The authentication principal name is the numeric user id (set at login). */
public final class CurrentUser {
    private CurrentUser() {}

    public static long id(Authentication auth) { return Long.parseLong(auth.getName()); }
}
