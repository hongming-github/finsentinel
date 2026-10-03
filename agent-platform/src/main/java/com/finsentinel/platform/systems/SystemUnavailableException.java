package com.finsentinel.platform.systems;

/** An external system did not answer, answered with an error, or answered something unreadable. */
public class SystemUnavailableException extends RuntimeException {

    private final String system;

    public SystemUnavailableException(String system, String detail) {
        super(system + " unavailable: " + detail);
        this.system = system;
    }

    public String system() {
        return system;
    }
}
