package ru.mcs.mcpsql.workspace;

/** The disk is nearly full, so new uploads are refused. */
public class InsufficientStorageException extends RuntimeException {

    public InsufficientStorageException(String message) {
        super(message);
    }
}
