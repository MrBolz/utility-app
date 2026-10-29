package io.github.mrbolz.utilityapp.bridge;

/** A bridge action failed; the message is shown to the user as is. */
final class BridgeException extends Exception {
    private static final long serialVersionUID = 1L;

    BridgeException(String message) {
        super(message);
    }
}
