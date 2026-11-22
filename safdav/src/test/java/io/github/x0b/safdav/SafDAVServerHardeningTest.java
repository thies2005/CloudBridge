package io.github.x0b.safdav;

import android.net.Uri;
import io.github.x0b.safdav.file.ItemAccess;
import io.github.x0b.safdav.file.ItemNotFoundException;
import io.github.x0b.safdav.file.SafItem;
import io.github.x0b.safdav.saf.DocumentsContractAccess;
import io.github.x0b.safdav.saf.ProviderPaths;
import io.github.x0b.safdav.saf.SafFastItem;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SafDAVServerHardeningTest {

    private static final SafItem DUMMY_ROOT = new SafItem() {
        @Override public String getLink() { return ""; }
        @Override public String getLastModified() { return "Mon, 01 Jan 2024 00:00:00 GMT"; }
        @Override public String getCreationDate() { return "2024-01-01T00:00:00Z"; }
        @Override public String getContentLength() { return "0"; }
        @Override public String getName() { return "root"; }
        @Override public String getContentType() { return "inode/directory"; }
        @Override public boolean isCollection() { return true; }
        @Override public String getETag() { return ""; }
        @Override public String getStatus() { return "HTTP/1.1 200 OK"; }
        @Override public long getLength() { return 0L; }
        @Override public List<? extends SafItem> getChildren() { return Collections.emptyList(); }
        @Override public Uri getUri() { return null; }
    };

    private static final ItemAccess NOOP_ACCESS = new ItemAccess() {
        @Override public SafItem createFile(Uri uri, String name, String mimeType, InputStream content, long len) { return null; }
        @Override public void createDirectory(Uri uri) {}
        @Override public void deleteItem(Uri uri) {}
        @Override public void copyItem(Uri srcUri, Uri dstUri) {}
        @Override public void moveItem(Uri srcUri, Uri dstUri) {}
        @Override public void renameItem(Uri item, Uri target) {}
        @Override public List list(Uri treeUri) { return Collections.emptyList(); }
        @Override public InputStream readFile(SafItem documentUri) { return null; }
        @Override public SafItem readMeta(Uri uri) { throw new ItemNotFoundException(); }
        @Override public void writeFile(Uri documentUri, InputStream stream, long len) {}
    };

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsEmptyCredentials() {
        new SafDAVServer(0, "", "secret", NOOP_ACCESS, new ProviderPaths(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNullPassword() {
        new SafDAVServer(0, "dav", null, NOOP_ACCESS, new ProviderPaths(null));
    }

    @Test
    public void ephemeralPortBindsLoopbackEnforcesAuthAndUsesDynamicBaseUrl() throws Exception {
        String password = SafAccessProvider.generateSessionPassword();
        String password2 = SafAccessProvider.generateSessionPassword();
        assertNotEquals(password, password2);
        assertTrue(password.length() >= 40);

        ProviderPaths stubPaths = new ProviderPaths(null) {
            @Override
            public SafItem getSafRootDirectory() {
                return DUMMY_ROOT;
            }
        };

        SafDAVServer server = new SafDAVServer(0, "dav", password, NOOP_ACCESS, stubPaths);
        server.start();
        try {
            int port = server.getListeningPort();
            assertTrue(port > 0);
            assertNotEquals(40404, port);
            assertEquals("http://127.0.0.1:" + port + "/", server.getBaseUrl());

            // Unauthenticated request must fail with 401
            HttpURLConnection unauth = (HttpURLConnection) new URL(server.getBaseUrl()).openConnection();
            unauth.setRequestMethod("GET");
            assertEquals(401, unauth.getResponseCode());
            unauth.disconnect();

            // Wrong password must fail with 401
            HttpURLConnection wrongAuth = (HttpURLConnection) new URL(server.getBaseUrl()).openConnection();
            wrongAuth.setRequestMethod("GET");
            wrongAuth.setRequestProperty("Authorization", SafBase64.encodeBasicAuth("dav", "wrong"));
            assertEquals(401, wrongAuth.getResponseCode());
            wrongAuth.disconnect();

            // Authenticated PROPFIND returns 207 referencing dynamic base URL
            try (Socket sock = new Socket("127.0.0.1", port)) {
                String req = "PROPFIND / HTTP/1.1\r\n"
                        + "Host: 127.0.0.1:" + port + "\r\n"
                        + "Authorization: " + SafBase64.encodeBasicAuth("dav", password) + "\r\n"
                        + "Depth: 0\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream os = sock.getOutputStream();
                os.write(req.getBytes(StandardCharsets.US_ASCII));
                os.flush();
                String rawResp = new String(sock.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(rawResp.startsWith("HTTP/1.1 207"));
                assertTrue(rawResp.contains("<D:href>" + server.getBaseUrl() + "</D:href>"));
            }
        } finally {
            server.stop();
        }
    }

    @Test
    public void extractMoveMappedPathAcceptsDynamicBaseUrlOnly() {
        String baseUrl = "http://127.0.0.1:45123/";
        assertEquals("/Internal Storage/a b.txt",
                SafDAVServer.extractMoveMappedPath("http://127.0.0.1:45123/Internal%20Storage/a%20b.txt", baseUrl));
        assertNull(SafDAVServer.extractMoveMappedPath("http://localhost:40404/Internal/a.txt", baseUrl));
        assertNull(SafDAVServer.extractMoveMappedPath(null, baseUrl));
    }

    @Test
    public void copyBoundedDoesNotOverReadKeepAliveStream() throws Exception {
        byte[] combined = "HELLO_WORLD_NEXT_HTTP_REQUEST".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(combined);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long written = DocumentsContractAccess.copyBounded(in, out, 11);
        assertEquals(11L, written);
        assertEquals("HELLO_WORLD", out.toString(StandardCharsets.UTF_8));
        byte[] remaining = in.readAllBytes();
        assertEquals("_NEXT_HTTP_REQUEST", new String(remaining, StandardCharsets.UTF_8));
    }

    @Test(expected = ItemNotFoundException.class)
    public void treeWalkSegmentsThrowsOnMissingSegment() {
        SafFastItem subDir = new SafFastItem(null, "sub", "inode/directory", 0, 0, true);
        DocumentsContractAccess.resolveTreeWalkSegments(
                "root",
                "/sub/missing",
                folder -> "root".equals(folder) ? Collections.singletonList(subDir) : Collections.emptyList(),
                SafFastItem::getName
        );
    }

    @Test
    public void treeWalkSegmentsResolvesMatchingNestedPath() {
        SafFastItem subDir = new SafFastItem(null, "sub", "inode/directory", 0, 0, true);
        SafFastItem targetFile = new SafFastItem(null, "file.txt", "text/plain", 0, 5, false);
        String resolved = DocumentsContractAccess.resolveTreeWalkSegments(
                "root",
                "/sub/file.txt",
                folder -> "root".equals(folder)
                        ? Collections.singletonList(subDir)
                        : ("sub".equals(folder) ? Collections.singletonList(targetFile) : Collections.emptyList()),
                SafFastItem::getName
        );
        assertEquals("file.txt", resolved);
    }

    @Test
    public void findDirectChildMatchesExactNameOnly() {
        SafFastItem a = new SafFastItem(null, "alpha", "text/plain", 0, 10, false);
        SafFastItem b = new SafFastItem(null, "beta", "text/plain", 0, 20, false);
        assertEquals(b, DocumentsContractAccess.findDirectChildByName(Arrays.asList(a, b), "beta"));
        assertNull(DocumentsContractAccess.findDirectChildByName(Arrays.asList(a, b), "gamma"));
    }
}
