package com.changescope.dto;

public record WebhookResponse(
    String status,
    String event,
    String deliveryId,
    String message,
    boolean duplicate
) {
    public WebhookResponse(String status, String event, String deliveryId, String message) {
        this(status, event, deliveryId, message, false);
    }

    public static WebhookResponse accepted(String event, String deliveryId, String message) {
        return new WebhookResponse("accepted", event, deliveryId, message, false);
    }

    public static WebhookResponse ignored(String event, String deliveryId, String message) {
        return new WebhookResponse("ignored", event, deliveryId, message, false);
    }

    public static WebhookResponse duplicate(String event, String deliveryId, String message) {
        return new WebhookResponse("duplicate", event, deliveryId, message, true);
    }
}
