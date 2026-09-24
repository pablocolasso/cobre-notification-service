package com.cobre.notification.adapter.in.web.dto;

import java.net.URI;
import java.util.Locale;

final class ApiValues {

    private ApiValues() {
    }

    static String lowercase(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    static String maskUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "invalid";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }
}
