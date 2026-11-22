package io.github.x0b.safdav;

import android.net.Uri;
import io.github.x0b.safdav.file.SafItem;
import io.github.x0b.safdav.xml.XmlResponseSerialization;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class XmlAndHeaderSanitizationTest {

    @Test
    public void propFindEscapesMaliciousContentTypeAndETag() {
        SafItem child = new SafItem() {
            @Override public String getLink() { return "evil.txt"; }
            @Override public String getLastModified() { return "Mon, 01 Jan 2024 00:00:00 GMT"; }
            @Override public String getCreationDate() { return "2024-01-01T00:00:00Z"; }
            @Override public String getContentLength() { return "5"; }
            @Override public String getName() { return "evil<1>&\".txt"; }
            @Override public String getContentType() { return "text/plain<script>&\"'"; }
            @Override public boolean isCollection() { return false; }
            @Override public String getETag() { return "etag<tag>&\"'"; }
            @Override public String getStatus() { return "HTTP/1.1 200 OK"; }
            @Override public long getLength() { return 5L; }
            @Override public List<? extends SafItem> getChildren() { return Collections.emptyList(); }
            @Override public Uri getUri() { return null; }
        };

        SafItem root = new SafItem() {
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
            @Override public List<? extends SafItem> getChildren() { return Collections.singletonList(child); }
            @Override public Uri getUri() { return null; }
        };

        String xml = new XmlResponseSerialization().propFindSerialize(root, "http://127.0.0.1:12345/", 1);
        assertFalse(xml.contains("<script>"));
        assertFalse(xml.contains("<tag>"));
        assertTrue(xml.contains("<D:getcontenttype>text/plain&lt;script&gt;&amp;&quot;&apos;</D:getcontenttype>"));
        assertTrue(xml.contains("<D:getetag>etag&lt;tag&gt;&amp;&quot;&apos;</D:getetag>"));
        assertTrue(xml.contains("<D:displayname>evil&lt;1&gt;&amp;&quot;.txt</D:displayname>"));
    }

    @Test
    public void sanitizeContentTypeRejectsCrlfAndInvalidMimeTypes() {
        assertEquals("application/octet-stream", SafDAVServer.sanitizeContentType(null));
        assertEquals("application/octet-stream", SafDAVServer.sanitizeContentType(""));
        assertEquals("application/octet-stream", SafDAVServer.sanitizeContentType("text/plain\r\nX-Injected: 1"));
        assertEquals("application/octet-stream", SafDAVServer.sanitizeContentType("text/plain\nSet-Cookie: pwn=1"));
        assertEquals("application/octet-stream", SafDAVServer.sanitizeContentType("notamime"));
        assertEquals("application/octet-stream", SafDAVServer.sanitizeContentType("text/plain\u0000"));
        assertEquals("text/plain", SafDAVServer.sanitizeContentType("text/plain"));
        assertEquals("text/plain; charset=utf-8", SafDAVServer.sanitizeContentType("text/plain; charset=utf-8"));
        assertEquals("application/vnd.android.package-archive",
                SafDAVServer.sanitizeContentType("application/vnd.android.package-archive"));
    }
}
