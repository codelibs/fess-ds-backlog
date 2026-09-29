/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.backlog;

/**
 * Thrown by {@link BacklogClient} when a Backlog API call finishes with a non-2xx response or a
 * transport failure, after any retry the client performs on its own.
 *
 * <p>
 * {@link #isFatal()} is {@code true} only for HTTP 401 and for Backlog error codes 11
 * (AuthenticationError) and 12 (RequiredMFAError): every further call would fail the same way.
 * A fatal exception propagates out of {@code BacklogDataStore#storeData} and aborts the crawl.
 * With {@code keep_expires_docs=false} in the handler parameters, {@code DataIndexHelper} still
 * runs {@code deleteOldDocs()} afterwards, so every document of this data config not re-indexed
 * in this run is deleted; by default those documents are kept until they expire. Everything
 * else, including exhausted retries, is not fatal and is recorded per item instead.
 * </p>
 */
public class BacklogApiException extends BacklogDataStoreException {

    private static final long serialVersionUID = 1L;

    /** The HTTP status code of the failing response, or 0 for a transport failure. */
    private final int status;

    /** The Backlog error code from {@code errors[0].code}, or 0 when the body carried none. */
    private final int code;

    /** Whether this error should abort the whole crawl. */
    private final boolean fatal;

    /**
     * Creates a new exception for a Backlog API error.
     *
     * @param path the request path relative to {@code /api/v2/}, e.g. {@code "projects"}
     * @param status the HTTP status code, or 0 for a transport failure
     * @param code the Backlog error code, or 0 if none
     * @param message the Backlog error message, or a synthesized one
     * @param fatal whether this error should abort the whole crawl
     */
    public BacklogApiException(final String path, final int status, final int code, final String message, final boolean fatal) {
        this(path, status, code, message, fatal, null);
    }

    /**
     * Creates a new exception for a Backlog API error that has an underlying cause.
     *
     * @param path the request path relative to {@code /api/v2/}, e.g. {@code "projects"}
     * @param status the HTTP status code, or 0 for a transport failure
     * @param code the Backlog error code, or 0 if none
     * @param message the Backlog error message, or a synthesized one
     * @param fatal whether this error should abort the whole crawl
     * @param cause the underlying failure, e.g. the transport error; may be {@code null}
     */
    public BacklogApiException(final String path, final int status, final int code, final String message, final boolean fatal,
            final Throwable cause) {
        super("Backlog API \"" + path + "\" returned HTTP " + status + (code != 0 ? " (code " + code + ")" : "") + ": " + message, cause);
        this.status = status;
        this.code = code;
        this.fatal = fatal;
    }

    /**
     * Returns the HTTP status code of the failing response.
     *
     * @return the HTTP status code, or 0 for a transport failure
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the Backlog error code.
     *
     * @return the error code, or 0 if the response carried none
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns whether this error should abort the whole crawl. See the class javadoc.
     *
     * @return true if the crawl must be aborted
     */
    public boolean isFatal() {
        return fatal;
    }

}
