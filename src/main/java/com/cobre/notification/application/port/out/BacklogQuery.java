package com.cobre.notification.application.port.out;

import java.time.Instant;

public interface BacklogQuery {

    BacklogSnapshot snapshot(Instant now);
}
