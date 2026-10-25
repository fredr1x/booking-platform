package dev.ydt.catalog.exception;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String cursor) {
        super("Invalid page cursor '" + cursor + "'");
    }
}
