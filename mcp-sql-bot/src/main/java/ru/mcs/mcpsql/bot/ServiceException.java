package ru.mcs.mcpsql.bot;

/** A refusal or a failure of mcp-sql-service; status 0 means the service could not be reached. */
public class ServiceException extends Exception {

    private final int status;
    private final String code;

    public ServiceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code == null ? "" : code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
