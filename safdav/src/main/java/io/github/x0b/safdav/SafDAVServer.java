package io.github.x0b.safdav;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import fi.iki.elonen.NanoHTTPD;
import io.github.x0b.safdav.file.FileAccessError;
import io.github.x0b.safdav.file.ItemAccess;
import io.github.x0b.safdav.file.ItemExistsException;
import io.github.x0b.safdav.file.ItemNotFoundException;
import io.github.x0b.safdav.file.SafException;
import io.github.x0b.safdav.file.SafItem;
import io.github.x0b.safdav.saf.DocumentsContractAccess;
import io.github.x0b.safdav.saf.ProviderPaths;
import io.github.x0b.safdav.xml.XmlResponseSerialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class SafDAVServer extends NanoHTTPD {

    private static final String TAG = "SafDAVServer";
    private static final String HOSTNAME = "127.0.0.1";
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    private static final Pattern SAFE_MIME_TYPE_PATTERN = Pattern.compile(
            "^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:\\s*;\\s*[A-Za-z0-9._-]+=(?:[A-Za-z0-9._-]+|\"[^\"\\r\\n\\\\]*\"))*$"
    );

    private final ItemAccess itemAccess;
    private final ProviderPaths paths;
    private final String requiredAuthHeader;

    /**
     * SafDAV binds to 127.0.0.1 on the specified port (or 0 for an ephemeral port).
     *
     * @param port     the device port to listen on (0 for ephemeral)
     * @param username non-empty username
     * @param password non-empty password
     * @param context  context for file access
     */
    public SafDAVServer(int port, @NonNull String username, @NonNull String password, @NonNull Context context) {
        this(port, username, password,
                new DocumentsContractAccess(context.getApplicationContext()),
                new ProviderPaths(context.getApplicationContext()));
    }

    @VisibleForTesting
    SafDAVServer(int port, @NonNull String username, @NonNull String password,
                 @NonNull ItemAccess itemAccess, @NonNull ProviderPaths paths) {
        super(HOSTNAME, port);
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("SafDAVServer requires non-empty username and password");
        }
        this.itemAccess = itemAccess;
        this.paths = paths;
        this.requiredAuthHeader = SafBase64.encodeBasicAuth(username, password);
        setAsyncRunner(new BoundedAsyncRunner());
    }

    public String getBaseUrl() {
        return "http://" + HOSTNAME + ":" + getListeningPort() + "/";
    }

    @VisibleForTesting
    public static String sanitizeContentType(@Nullable String mimeType) {
        if (mimeType == null) {
            return DEFAULT_MIME_TYPE;
        }
        for (int i = 0; i < mimeType.length(); i++) {
            char c = mimeType.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return DEFAULT_MIME_TYPE;
            }
        }
        String trimmed = mimeType.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_MIME_TYPE;
        }
        if (!SAFE_MIME_TYPE_PATTERN.matcher(trimmed).matches()) {
            return DEFAULT_MIME_TYPE;
        }
        return trimmed;
    }

    private static String sanitizeHeaderToken(@Nullable String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (!checkAuthorization(session)) {
            Log.e(TAG, "serve: request not (correctly) authenticated");
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, null, null);
        }
        switch (session.getMethod()) {
            // reading methods
            case GET:
                return onGet(session);
            case PROPFIND:
                return onPropFind(session);
            // writing methods
            case PROPPATCH:
                return onPropPatch(session);
            case MKCOL:
                return onMkCol(session);
            case COPY:
                return onCopy(session);
            case DELETE:
                return onDelete(session);
            case MOVE:
                return onMove(session);
            case PUT:
                return onPut(session);
            // NanoHttpd will serve 501
            default:
                return notImplementedResponse(session, "serve");
        }
    }

    private boolean checkAuthorization(IHTTPSession session) {
        if (null == requiredAuthHeader || requiredAuthHeader.isEmpty()) {
            return false;
        }
        String suppliedAuthHeader = session.getHeaders().get("authorization");
        if (null == suppliedAuthHeader) {
            return false;
        }
        return MessageDigest.isEqual(
                requiredAuthHeader.getBytes(StandardCharsets.UTF_8),
                suppliedAuthHeader.getBytes(StandardCharsets.UTF_8));
    }

    private Response badRequest(String signature) {
        Log.d(TAG, signature + ": 400 Bad Request");
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/html", "<!doctype html><html><body><h1>BAD REQEST</h1></body></html>");
    }

    private Response notFound(String signature) {
        Log.d(TAG, signature + ": 404 Not Found");
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
    }

    private Response forbidden(String signature) {
        Log.d(TAG, signature + ": 403 Forbidden");
        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
    }

    private Response notAllowed(String signature) {
        Log.d(TAG, signature + ": 405 Not Allowed");
        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, null, null);
    }

    private Response conflict(String signature) {
        Log.d(TAG, signature + ": 409 Conflict");
        return newFixedLengthResponse(Response.Status.CONFLICT, null, null);
    }

    private Response preconditionFailed(String signature) {
        Log.d(TAG, signature + ": 412 Precondition Failed");
        return newFixedLengthResponse(Response.Status.PRECONDITION_FAILED, null, null);
    }

    private Response ok(String message, String signature) {
        Log.d(TAG, signature + ": 200 Ok");
        return newFixedLengthResponse(message);
    }

    private Response okXml(String message, String signature) {
        Log.d(TAG, signature + ": 207 Multi-Status");
        return newFixedLengthResponse(Response.Status.MULTI_STATUS, "text/xml", message);
    }

    private Response created(String signature) {
        Log.d(TAG, signature + ": 201 Created");
        return newFixedLengthResponse(Response.Status.CREATED, null, null);
    }

    private Response noContent(String signature) {
        Log.d(TAG, signature + ": 204 No Content");
        return newFixedLengthResponse(Response.Status.NO_CONTENT, null, null);
    }

    private Response internalError(String signature) {
        Log.d(TAG, signature + ": 500 Internal Server Error");
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal Error");
    }

    private Response notImplementedResponse(IHTTPSession session, String signature) {
        // Don't dump headers: they may include the local WebDAV Authorization credential.
        Log.e(TAG, signature + ": Request { method " + session.getMethod()
                + ", uri: " + session.getUri() + " }");
        Log.e(TAG, signature + ": not implemented", new Exception("Stack Trace"));
        return null;
    }

    /**
     * Retrieve an entity
     */
    protected Response onGet(IHTTPSession session) {
        String requestUri = session.getUri();
        Uri uri;
        try {
            uri = paths.getUriByMappedPath(requestUri);
        } catch (ItemNotFoundException e) {
            return notFound("onGet");
        }
        SafItem file;
        try {
            file = itemAccess.readMeta(uri);
        } catch (ItemNotFoundException e) {
            return notFound("onGet");
        }
        InputStream stream = itemAccess.readFile(file);
        if (null != stream) {
            return newFixedLengthResponse(Response.Status.OK, sanitizeContentType(file.getContentType()),
                    stream, file.getLength());
        }
        return badRequest("onGet");
    }

    private Response closeBadRequest() {
        Response response = badRequest("onPropFind");
        response.closeConnection(true);
        return response;
    }

    protected Response onPropFind(IHTTPSession session) {
        // Consume optional XML properties before the connection is reused.
        try {
            String contentLength = session.getHeaders().get("content-length");
            int length = contentLength == null ? 0 : Integer.parseInt(contentLength);
            if (length < 0 || length > 65536 || session.getHeaders().containsKey("transfer-encoding")) {
                return closeBadRequest();
            }
            byte[] buffer = new byte[4096];
            while (length > 0) {
                int read = session.getInputStream().read(buffer, 0, Math.min(length, buffer.length));
                if (read < 0) return closeBadRequest();
                length -= read;
            }
        } catch (IOException | NumberFormatException e) {
            return closeBadRequest();
        }
        String hDepth = session.getHeaders().get("depth");
        int depth;
        try {
            depth = Integer.parseInt(hDepth);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid depth header, assuming `1´");
            depth = 1;
        }

        String path = session.getUri();
        Log.d(TAG, "onPropFind: PROPFIND " + path);

        SafItem directory;
        if ("/".equals(path)) {
            directory = paths.getSafRootDirectory();
        } else {
            try {
                Uri uri = paths.getUriByMappedPath(path);
                directory = itemAccess.readMeta(uri);
            } catch (ItemNotFoundException e) {
                return notFound("onPropFind");
            } catch (FileAccessError e) {
                return internalError("onPropFind");
            }
        }
        String subPath = path != null && path.length() > 1 ? encodePathSegments(path.substring(1)) : "";
        String requestUri = getBaseUrl() + subPath;
        String responseXml = new XmlResponseSerialization().propFindSerialize(directory, requestUri, depth);
        return okXml(responseXml, "onPropFind");
    }

    protected Response onPropPatch(IHTTPSession session) {
        return notImplementedResponse(session, "onPropPatch");
    }

    protected Response onMkCol(IHTTPSession session) {
        String path = session.getUri();
        Log.d(TAG, "onMkCol: MKCOL " + path);
        Uri uri;
        try {
            uri = paths.getUriByMappedPath(path);
        } catch (ItemNotFoundException e) {
            return forbidden("onMkCol");
        }
        try {
            itemAccess.createDirectory(uri);
        } catch (ItemNotFoundException e) {
            return notFound("onMkCol");
        } catch (ItemExistsException e) {
            return notAllowed("onMkCol");
        } catch (SecurityException | IllegalStateException e) {
            return forbidden("onMkCol");
        }
        return created("onMkCol");
    }

    protected Response onDelete(IHTTPSession session) {
        String path = session.getUri();
        Log.d(TAG, "onDelete: DELETE " + path);
        Uri uri;
        try {
            uri = paths.getUriByMappedPath(path);
        } catch (ItemNotFoundException e) {
            return forbidden("onDelete");
        }
        try {
            itemAccess.deleteItem(uri);
        } catch (FileAccessError e) {
            return internalError("onDelete");
        } catch (ItemNotFoundException e) {
            return notFound("onDelete");
        }
        return noContent("onDelete");
    }

    protected Response onPut(IHTTPSession session) {
        String path = session.getUri();
        String mime = session.getHeaders().get("content-type");
        String lengthValue = session.getHeaders().get("content-length");
        Log.d(TAG, "onPut: PUT " + path + "; " + mime + "; " + lengthValue + " byte");
        InputStream is = session.getInputStream();
        long length;
        try {
            length = Long.parseLong(lengthValue);
        } catch (NumberFormatException e) {
            return badRequest("onPut");
        }
        if (length < 0) {
            return badRequest("onPut");
        }
        if (null == mime) {
            mime = DEFAULT_MIME_TYPE;
        }

        Uri uri;
        try {
            uri = paths.getUriByMappedPath(path);
        } catch (ItemNotFoundException e) {
            return forbidden("onPut");
        }

        SafItem item;
        String etag;
        try {
            String name = path.substring(path.lastIndexOf('/') + 1);
            try {
                item = itemAccess.readMeta(uri);
                itemAccess.writeFile(item.getUri(), is, length);
            } catch (ItemNotFoundException e) {
                item = itemAccess.createFile(uri, name, mime, is, length);
                etag = "\"" + sanitizeHeaderToken(item.getETag()) + "\"";
                Response response = created("onPut");
                response.addHeader("etag", etag);
                return response;
            }
        } catch (SafException e) {
            return internalError("onPut");
        }
        etag = "\"" + sanitizeHeaderToken(item.getETag()) + "\"";
        Response response = created("onPut");
        response.addHeader("etag", etag);
        return response;
    }

    protected Response onCopy(IHTTPSession session) {
        return notImplementedResponse(session, "onCopy");
    }

    protected Response onMove(IHTTPSession session) {
        String path = session.getUri();
        Uri src;
        try {
            src = paths.getUriByMappedPath(path);
        } catch (ItemNotFoundException e) {
            return notFound("onMove");
        }
        boolean noOverwrite = "F".equals(session.getHeaders().get("overwrite"));
        String destination = session.getHeaders().get("destination");
        String mappedDest = extractMoveMappedPath(destination, getBaseUrl());
        if (mappedDest == null) {
            return badRequest("onMove");
        }
        Uri dst;
        try {
            dst = paths.getUriByMappedPath(mappedDest);
        } catch (ItemNotFoundException e) {
            return forbidden("onMove");
        }

        boolean exists = true;
        try {
            itemAccess.readMeta(dst);
        } catch (ItemNotFoundException e) {
            exists = false;
        }
        if (noOverwrite && exists) {
            return preconditionFailed("onMove");
        }

        itemAccess.moveItem(src, dst);

        if (exists) {
            return noContent("onMove");
        } else {
            return created("onMove");
        }
    }

    @VisibleForTesting
    @Nullable
    static String extractMoveMappedPath(@Nullable String destination, @NonNull String baseUrl) {
        if (destination == null || !destination.startsWith(baseUrl)) {
            return null;
        }
        String rawPath = destination.substring(baseUrl.length() - 1);
        return percentDecode(rawPath);
    }

    @VisibleForTesting
    static String encodePathSegments(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '-' || c == '_' || c == '.' || c == '~'
                    || c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    @Nullable
    private static String percentDecode(String input) {
        if (input.indexOf('%') < 0) {
            return input;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '%') {
                if (i + 2 >= input.length()) {
                    return null;
                }
                int hi = Character.digit(input.charAt(i + 1), 16);
                int lo = Character.digit(input.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] utf8 = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class BoundedAsyncRunner implements AsyncRunner {
        private final Set<ClientHandler> running = Collections.synchronizedSet(new HashSet<>());
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,
                16,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "SafDAV-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        @Override
        public void closeAll() {
            List<ClientHandler> snapshot;
            synchronized (running) {
                snapshot = new ArrayList<>(running);
            }
            for (ClientHandler handler : snapshot) {
                handler.close();
            }
            executor.shutdownNow();
        }

        @Override
        public void closed(ClientHandler clientHandler) {
            running.remove(clientHandler);
        }

        @Override
        public void exec(ClientHandler clientHandler) {
            running.add(clientHandler);
            try {
                executor.execute(clientHandler);
            } catch (RejectedExecutionException e) {
                running.remove(clientHandler);
                clientHandler.close();
            }
        }
    }
}
