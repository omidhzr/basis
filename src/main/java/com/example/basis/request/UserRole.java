package com.example.basis.request;

/**
 * The role claimed by the caller. Carried by the X-User-Role header, standing
 * in for a claim that would arrive in a JWT in production.
 */
public enum UserRole {

    RELATIONSHIP_MANAGER,
    REVIEWER
}
