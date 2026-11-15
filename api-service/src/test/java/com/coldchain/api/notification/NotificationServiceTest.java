package com.coldchain.api.notification;

import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private final NotificationCooldown cooldown = mock(NotificationCooldown.class);
    private final TelegramNotifier telegram = mock(TelegramNotifier.class);
    private final NotificationService service = new NotificationService(cooldown, new AlertMessageFormatter(), telegram);

    private final AlertEvent alert = TestAlerts.opened("nile-fridge-01", AlertType.TEMP_EXCURSION);

    @Test
    void sendsWhenNoCooldownIsActive() {
        when(cooldown.tryAcquire(alert)).thenReturn(true);

        service.notifyOpened(alert);

        verify(telegram).send(anyString());
    }

    @Test
    void doesNotSendDuringTheCooldown() {
        when(cooldown.tryAcquire(alert)).thenReturn(false);

        service.notifyOpened(alert);

        verify(telegram, never()).send(anyString());
    }

    @Test
    void releasesTheCooldownWhenSendingFailsSoARetryCanNotify() {
        when(cooldown.tryAcquire(alert)).thenReturn(true);
        doThrow(new NotificationException("Telegram down")).when(telegram).send(anyString());

        assertThatThrownBy(() -> service.notifyOpened(alert)).isInstanceOf(NotificationException.class);

        verify(cooldown).release(alert);
    }
}
