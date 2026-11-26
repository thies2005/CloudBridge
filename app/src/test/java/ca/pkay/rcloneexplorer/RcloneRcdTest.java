package ca.pkay.rcloneexplorer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import okhttp3.Authenticator;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class RcloneRcdTest {

    @Test
    public void rotatesRandomHighEntropyRcPasswords() {
        String p1 = RcloneRcd.initPass();
        String p2 = RcloneRcd.initPass();
        assertNotNull(p1);
        assertNotNull(p2);
        assertTrue(p1.length() >= 32);
        assertNotEquals(p1, p2);
    }

    @Test
    public void buildRcdCommandArgsUsesUnixSocketAndOmitsCredentialsFromArgv() {
        File sock = new File("/data/user/0/de.schuelken.cloudbridge/files/rcd.sock");
        List<String> args = RcloneRcd.buildRcdCommandArgs(
                "/lib/librclone.so", "/files/rclone.conf", sock, "999", null);
        assertTrue(args.contains("--rc-addr"));
        assertTrue(args.contains("unix://" + sock.getAbsolutePath()));
        assertFalse(args.contains("--rc-user"));
        assertFalse(args.contains("--rc-pass"));
        int transfersIdx = args.indexOf("--transfers");
        assertTrue(transfersIdx >= 0);
        assertEquals("32", args.get(transfersIdx + 1));
        assertEquals("rcd", args.get(args.size() - 1));
    }

    @Test
    public void authenticatorCaps401RetryToOneAttempt() throws IOException {
        Authenticator auth = RcloneRcd.buildRcdAuthenticator("admin", "secret");
        Request firstReq = new Request.Builder().url("http://localhost/core/stats").build();
        Response unauthenticatedResp = new Response.Builder()
                .request(firstReq)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .build();
        Request retry = auth.authenticate(null, unauthenticatedResp);
        assertNotNull(retry);
        assertNotNull(retry.header("Authorization"));

        Response second401 = unauthenticatedResp.newBuilder().request(retry).build();
        assertNull(auth.authenticate(null, second401));
    }

    @Test
    public void jacksonMapperDeserializesRcdResponses() throws Exception {
        ObjectMapper mapper = RcloneRcd.createObjectMapper();

        String statusJson = "{"
                + "\"id\": 42,"
                + "\"finished\": true,"
                + "\"success\": true,"
                + "\"startTime\": \"2026-11-22T12:00:00Z\","
                + "\"endTime\": \"2026-11-22T12:00:05Z\","
                + "\"unknownFutureField\": 123"
                + "}";
        RcloneRcd.JobStatusResponse status = mapper.readValue(statusJson, RcloneRcd.JobStatusResponse.class);
        assertEquals(42, status.id);
        assertTrue(status.finished);
        assertTrue(status.success);
        assertTrue(status.endTime > status.startTime);

        String listJson = "{\"remotes\": [\"gdrive\", \"b2\"]}";
        RcloneRcd.ListRemotesResponse remotes = mapper.readValue(listJson, RcloneRcd.ListRemotesResponse.class);
        assertEquals(2, remotes.remotes.length);
        assertEquals("gdrive", remotes.remotes[0]);

        String itemJson = "{\"Path\":\"docs/a.pdf\",\"Name\":\"a.pdf\",\"MimeType\":\"application/pdf\",\"Size\":1024,\"ModTime\":\"2026-11-22T10:00:00Z\",\"IsDir\":false}";
        RcloneRcd.ListItem item = mapper.readValue(itemJson, RcloneRcd.ListItem.class);
        assertEquals("a.pdf", item.name);
        assertEquals(1024L, item.size);
        assertFalse(item.isDir);
    }
}
