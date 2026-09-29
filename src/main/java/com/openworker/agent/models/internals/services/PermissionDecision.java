package com.openworker.agent.models.internals.services;

public record PermissionDecision(boolean allowed, boolean needsUserApproval, String reason) {
    public static PermissionDecision allow() {
        return new PermissionDecision(true, false, "Allowed");
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, false, reason);
    }

    public static PermissionDecision askUser(String reason) {
        return new PermissionDecision(false, true, reason);
    }
}
