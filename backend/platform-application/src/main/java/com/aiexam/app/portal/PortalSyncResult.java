package com.aiexam.app.portal;

public record PortalSyncResult(int fetched, int created, int updated, boolean success, String error) {}
