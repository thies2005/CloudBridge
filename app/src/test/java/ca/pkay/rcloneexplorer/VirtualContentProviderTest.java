package ca.pkay.rcloneexplorer;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import ca.pkay.rcloneexplorer.Items.RemoteItem;
import io.github.x0b.safdav.file.SafConstants;

public class VirtualContentProviderTest {

    VirtualContentProvider provider;

    @Before
    public void setUp() throws Exception {
        provider = new VirtualContentProvider();
    }

    @Test
    public void getChildName() {
        assertEquals("child:", provider.getChildName("child:"));
        assertEquals("child", provider.getChildName("remote:child"));
        assertEquals("child", provider.getChildName("remote:parent/child"));
        assertEquals("child", provider.getChildName("remote:parent/child/"));
        assertEquals("child", provider.getChildName("remotes/remote:child"));
        assertEquals("child", provider.getChildName("remotes/remote:/child"));
        assertEquals("child", provider.getChildName("remotes/remote:/child/"));
        assertEquals("child", provider.getChildName("remotes/remote:/parent/child"));
    }

    @Test
    public void getParent() {
        assertEquals("remote:", provider.getParent("remote:child"));
        assertEquals("remote:parent", provider.getParent("remote:parent/child"));
        assertEquals("remote:parent", provider.getParent("remote:parent/child/"));
        assertEquals("remotes/remote:", provider.getParent("remotes/remote:child"));
        assertEquals("remotes/remote:", provider.getParent("remotes/remote:/child"));
        assertEquals("remotes/remote:", provider.getParent("remotes/remote:/child/"));
        assertEquals("remotes/remote:/parent", provider.getParent("remotes/remote:/parent/child"));
    }

    @Test
    public void getRclonePath() {
        assertEquals("dir/file.pdf", provider.getRclonePath("remote:/dir/file.pdf"));
        assertEquals("dir/file.pdf", provider.getRclonePath("remotes/remote:/dir/file.pdf"));
    }

    @Test
    public void isChildDocumentId() {
        assertTrue(provider.isChildDocument(VirtualContentProvider.ROOT_DOC_PREFIX, VirtualContentProvider.ROOT_DOC_PREFIX + "sub/doc"));
        assertFalse(provider.isChildDocument(VirtualContentProvider.ROOT_DOC_PREFIX + "sub/doc", VirtualContentProvider.ROOT_DOC_PREFIX));
    }

    @Test
    public void getTargetDocumentId() {
        assertEquals("remotes/remote:/item", VirtualContentProvider.getTargetDocumentId("remotes/remote:/dir/item", "remotes/remote:"));
        assertEquals("remotes/remote:/item", VirtualContentProvider.getTargetDocumentId("remotes/remote:/dir/item", "remotes/remote:/"));
    }

    @Test(expected = VirtualContentProvider.DocumentIdException.class)
    public void getTargetDocumentIdRooted() {
        VirtualContentProvider.getTargetDocumentId(
                VirtualContentProvider.getRootedDocumentId("remote:/dir/item"),
                VirtualContentProvider.getRootedDocumentId("remotes/remote:/"));
    }

    @Test
    public void getTargetByChild() {
        assertEquals("remotes/remote:/child",
                VirtualContentProvider.getTargetByChild("remotes/remote:/", "child"));
        assertEquals("remotes/remote:/child",
                VirtualContentProvider.getTargetByChild("remotes/remote:", "child"));
    }

    @Test
    public void getNoRootId() {
        assertEquals("remote:/item", VirtualContentProvider.getNoRootId(
                VirtualContentProvider.ROOT_DOC_PREFIX + "remote:/item"));
    }

    @Test
    public void getRootedDocumentId() {
        assertEquals(VirtualContentProvider.ROOT_DOC_PREFIX + "remote:/item",
                VirtualContentProvider.getRootedDocumentId("remote:/item"));
    }

    @Test
    public void getShortId() {
        assertEquals("remote:/item", VirtualContentProvider.getShortId(
                VirtualContentProvider.ROOT_DOC_PREFIX + "remote:/item"));
    }

    @Test
    public void isRemoteDocument() {
        assertTrue(VirtualContentProvider.isRemoteDocument(
                VirtualContentProvider.ROOT_DOC_PREFIX + "gdrive:"));
    }

    @Test
    public void getRelativeItemPath() {
        String lsPath = "home/taxes/2020.pdf";
        String expectedPath = "/home/taxes/2020.pdf";

        String actualPath = VirtualContentProvider.getRelativeItemPath(lsPath);

        assertEquals(expectedPath, actualPath);
    }

    @Test
    public void getRemoteName() {
        String documentId = "dropbox:/sheet.xls";
        String expectedName = "dropbox";

        String actualName = VirtualContentProvider.getRemoteName(documentId);

        assertEquals(actualName, expectedName);
    }

    @Test
    public void validatesDisplayNamesStrictly() {
        assertFalse(VirtualContentProvider.isSafeDisplayName(null));
        assertFalse(VirtualContentProvider.isSafeDisplayName(""));
        assertFalse(VirtualContentProvider.isSafeDisplayName("."));
        assertFalse(VirtualContentProvider.isSafeDisplayName(".."));
        assertFalse(VirtualContentProvider.isSafeDisplayName("sub/file"));
        assertFalse(VirtualContentProvider.isSafeDisplayName("sub\\file"));
        assertFalse(VirtualContentProvider.isSafeDisplayName("bad:name"));
        assertFalse(VirtualContentProvider.isSafeDisplayName("bad\0name"));
        assertTrue(VirtualContentProvider.isSafeDisplayName("report 2026 (final).pdf"));
    }

    @Test
    public void validatesDocumentPathsStrictly() {
        assertFalse(VirtualContentProvider.isSafeDocumentPath(null));
        assertFalse(VirtualContentProvider.isSafeDocumentPath(""));
        assertFalse(VirtualContentProvider.isSafeDocumentPath("rclone/remotes/gdrive:../secret"));
        assertFalse(VirtualContentProvider.isSafeDocumentPath("rclone/remotes/..:secret"));
        assertFalse(VirtualContentProvider.isSafeDocumentPath("rclone/remotes/gdrive:/a/./b"));
        assertFalse(VirtualContentProvider.isSafeDocumentPath("rclone/remotes/gdrive:\\evil"));
        assertFalse(VirtualContentProvider.isSafeDocumentPath("rclone/remotes/gdrive:/evil\0name"));
        assertTrue(VirtualContentProvider.isSafeDocumentPath(VirtualContentProvider.ROOT_DOC_ID));
        assertTrue(VirtualContentProvider.isSafeDocumentPath("rclone/remotes/gdrive:/notes..v2/file.txt"));
    }

    @Test
    public void filtersLocalSafAndPathAliasesOutOfVcpRoots() {
        RemoteItem saf = new RemoteItem("local-saf", SafConstants.SAF_REMOTE_NAME);
        assertFalse(VirtualContentProvider.isVcpAllowedRemote(saf, false));

        RemoteItem local = new RemoteItem("local", "local");
        assertFalse(VirtualContentProvider.isVcpAllowedRemote(local, false));

        RemoteItem pathAlias = new RemoteItem("alias-local", "local");
        pathAlias.setIsAlias(true);
        pathAlias.setIsPathAlias(true);
        assertFalse(VirtualContentProvider.isVcpAllowedRemote(pathAlias, false));

        RemoteItem wrappedSaf = new RemoteItem("crypt-saf", "webdav");
        wrappedSaf.setIsCrypt(true);
        assertFalse(VirtualContentProvider.isVcpAllowedRemote(wrappedSaf, true));

        RemoteItem drive = new RemoteItem("gdrive", "drive");
        assertTrue(VirtualContentProvider.isVcpAllowedRemote(drive, false));
    }
}