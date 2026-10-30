package io.github.mrbolz.utilityapp.tuya;

/** A Tuya LAN request failed for a reason the app can explain to the user. */
public class TuyaException extends Exception {
    public enum Reason {
        /** The device does not accept the local key (or the protocol version is wrong). */
        KEY_REJECTED,
        /** The device refused or reset the connection, usually because another app is connected to it. */
        BUSY,
        /** Nothing answers at the address. */
        UNREACHABLE,
        /** The device accepted the connection but did not answer in time. */
        TIMEOUT,
        /** The device sent something that is not a valid Tuya message. */
        BAD_RESPONSE,
        /** The device answered with an error code. */
        DEVICE_ERROR,
        UNSUPPORTED_VERSION
    }

    private final Reason reason;

    public TuyaException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TuyaException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
