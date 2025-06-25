package de.sventorben.keycloak.kommons.auth;

import org.keycloak.models.UserModel;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

final class UnusualLoginTimeUserWrapper {
    private static final String USER_ATTRIBUTE_USUAL_LOGIN_TIMES = "kommons.usualLoginTimes";

    private final UserModel user;

    UnusualLoginTimeUserWrapper(UserModel user) {
        this.user = user;
    }

    LocalTime getMinTime() {
        return user.getAttributeStream(USER_ATTRIBUTE_USUAL_LOGIN_TIMES)
            .map(LocalTime::parse)
            .min(Comparator.naturalOrder()).orElse(LocalTime.MIN);
    }

    LocalTime getMaxTime() {
        return user.getAttributeStream(USER_ATTRIBUTE_USUAL_LOGIN_TIMES)
            .map(LocalTime::parse)
            .max(Comparator.naturalOrder()).orElse(LocalTime.MAX);
    }

    void addSuccessfulLoginTime(LocalTime time) {
        List<String> usualTimes = user.getAttributeStream(USER_ATTRIBUTE_USUAL_LOGIN_TIMES).collect(Collectors.toList());
        usualTimes.add(0, time.format(DateTimeFormatter.ISO_LOCAL_TIME));
        usualTimes = usualTimes.subList(0, Math.min(5, usualTimes.size()));
        user.setAttribute(USER_ATTRIBUTE_USUAL_LOGIN_TIMES, usualTimes);
    }
}
