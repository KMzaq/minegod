package com.sande.mythictrpg.interaction.start;

public record DeliverySummary(DeliveryStatus status, int attempted, int sent) {
    public DeliverySummary {
        if (attempted < 1 || sent < 0 || sent > attempted) {
            throw new IllegalArgumentException("Invalid delivery counts");
        }
        DeliveryStatus expected = sent == attempted ? DeliveryStatus.ALL_SENT
                : sent == 0 ? DeliveryStatus.FAILED : DeliveryStatus.PARTIAL;
        if (status != expected) {
            throw new IllegalArgumentException("Delivery status does not match counts");
        }
    }

    public static DeliverySummary of(int attempted, int sent) {
        return new DeliverySummary(sent == attempted ? DeliveryStatus.ALL_SENT
                : sent == 0 ? DeliveryStatus.FAILED : DeliveryStatus.PARTIAL, attempted, sent);
    }
}
