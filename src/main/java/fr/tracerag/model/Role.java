package fr.tracerag.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum Role {
    GUEST(EnumSet.of(Classification.PUBLIC)),
    EMPLOYEE(EnumSet.of(Classification.PUBLIC, Classification.INTERNAL)),
    ADMIN(EnumSet.allOf(Classification.class));

    private final Set<Classification> access;

    Role(Set<Classification> access) {
        this.access = Set.copyOf(access);
    }

    public boolean canRead(Classification classification) {
        return access.contains(classification);
    }

    public static Role fromHeader(String value) {
        try {
            return value == null || value.isBlank()
                    ? EMPLOYEE
                    : valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("X-Role doit valoir guest, employee ou admin.");
        }
    }
}

