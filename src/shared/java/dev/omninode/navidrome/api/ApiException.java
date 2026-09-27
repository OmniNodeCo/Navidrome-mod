package dev.omninode.navidrome.api;

/** A user-displayable error. Never contains an authentication URL or secret. */
public final class ApiException extends RuntimeException {
    public ApiException(String message) { super(message); }
    public ApiException(String message, Throwable cause) { super(message, cause); }
}
