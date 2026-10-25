package io.github.oluwafemiadeoye.rewards.port;

import io.github.oluwafemiadeoye.rewards.domain.Notification;

/** Delivers notifications to users through a provider (push, e-mail, SMS). */
public interface Notifier {

    /**
     * Delivers one notification.
     *
     * @param notification the message to deliver
     * @throws NotificationDeliveryException when the provider rejects the message or times out
     */
    void send(Notification notification);
}
