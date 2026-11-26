package io.github.x0b.safdav.saf;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import io.github.x0b.safdav.file.FileAccessError;
import io.github.x0b.safdav.file.ItemAccess;
import io.github.x0b.safdav.file.ItemExistsException;
import io.github.x0b.safdav.file.ItemNotFoundException;
import io.github.x0b.safdav.file.SafException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A variant of an access provider using lower level DocumentsContract and ContentResolver#query apis.
 * Should be faster than the DocumentFile based access methods.
 *
 * Especially for directory lists, where all required parameters are queried at once for all files rather than letting
 * the framework create DocumentFiles for you which will query the content resolver for _each and every_ file property
 * on access, e.g. serialization query time == t_single-cell-query * num_files * num_properties.
 */
public class DocumentsContractAccess implements ItemAccess<SafFastItem> {

    private static final String TAG = "DocumentsContractAccess";
    private final Context context;
    private final SafFileAccess sfa;

    public DocumentsContractAccess(Context context) {
        this.context = context;
        this.sfa = new SafFileAccess(context);
    }

    @Override
    public SafFastItem createFile(Uri uri, String name, String mimeType, InputStream content, long len) {
        Uri parentUri = getParent(uri);
        try {
            Uri fileUri = DocumentsContract.createDocument(context.getContentResolver(), parentUri,
                    mimeType, getDisplayName(uri));
            if (fileUri == null) {
                Log.w(TAG, "SAF provider failed to create document: " + uri);
                throw new FileAccessError();
            }
            writeFile(fileUri, content, len);
            //return sfa.readMeta(fileUri);
            return readMeta(uri);
        } catch (FileNotFoundException e) {
            throw new ItemNotFoundException(e);
        }
    }

    @Override
    public void createDirectory(Uri uri) {
        try {
            readMeta(uri);
        } catch (ItemNotFoundException e) {
            try {
                DocumentsContract.createDocument(context.getContentResolver(), getParent(uri),
                        DocumentsContract.Document.MIME_TYPE_DIR, getDisplayName(uri));
            } catch (FileNotFoundException en) {
                throw new ItemNotFoundException(en);
            }
            return;
        }
        throw new ItemExistsException();
    }

    @Override
    public void deleteItem(Uri uri) {
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), buildHierarchicalDocumentsUri(uri));
        } catch (FileNotFoundException e) {
            throw new ItemNotFoundException(e);
        }
    }

    @Override
    public void copyItem(Uri srcUri, Uri dstUri) {
        sfa.copyItem(srcUri, dstUri);
    }

    @Override
    public void moveItem(Uri srcStubUri, Uri targetStubUri) {
        Uri srcUri = buildHierarchicalDocumentsUri(srcStubUri);
        Uri srcParentUri = getParent(srcStubUri);
        Uri dstParentUri = getParent(targetStubUri);

        if (srcParentUri.equals(dstParentUri)) {
            renameItem(srcStubUri, targetStubUri);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                DocumentsContract.moveDocument(context.getContentResolver(), srcUri, srcParentUri, dstParentUri);
            } catch (FileNotFoundException e) {
                throw new ItemNotFoundException();
            }
        } else {
            sfa.moveItem(srcStubUri, targetStubUri);
        }
    }

    @Override
    public void renameItem(Uri item, Uri target) {
        Uri documentUri = buildHierarchicalDocumentsUri(item);
        try {
            DocumentsContract.renameDocument(context.getContentResolver(), documentUri, getDisplayName(target));
        } catch (FileNotFoundException e) {
            throw new ItemNotFoundException();
        }
    }

    @Override
    public List<SafFastItem> list(Uri treeUri) {
        ContentResolver contentResolver = context.getContentResolver();
        Uri childDocsUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(treeUri));
        final ArrayList<SafFastItem> results = new ArrayList<>();

        try (Cursor cursor = contentResolver.query(childDocsUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
        }, null, null, null)) {
            if (null == cursor) {
                Log.w(TAG, "SAF provider returned null cursor for " + childDocsUri);
                throw new FileAccessError();
            }
            while (cursor.moveToNext()) {
                final String documentId = cursor.getString(0);
                String mimeType = cursor.getString(2);
                boolean isDirectory = false;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    mimeType = "inode/directory";
                    isDirectory = true;
                } else if (mimeType == null) {
                    mimeType = "application/octet-stream";
                }
                SafFastItem item = new SafFastItem(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        cursor.getString(1),
                        mimeType,
                        cursor.getLong(3),
                        cursor.getLong(4),
                        isDirectory
                );
                results.add(item);
            }
        } catch (SafException e) {
            throw e;
        } catch (Exception e) {
            Log.w("DocumentsContractAccess", "Failed query: " + e);
            throw new FileAccessError(e);
        }
        return results;
    }

    @Override
    public InputStream readFile(SafFastItem documentUri) {
        try {
            return context.getContentResolver().openInputStream(documentUri.getUri());
        } catch (FileNotFoundException e) {
            throw new ItemNotFoundException(e);
        }
    }

    @Override
    public SafFastItem readMeta(Uri uri) {
        ContentResolver contentResolver = context.getContentResolver();
        Uri docUri = buildHierarchicalDocumentsUri(uri);
        try (Cursor cursor = contentResolver.query(docUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
        }, null, null, null)) {
            if (null == cursor) {
                throw new ItemNotFoundException();
            }
            if (cursor.moveToNext()) {
                final String documentId = cursor.getString(0);
                String mimeType = cursor.getString(2);
                if (null == mimeType) {
                    mimeType = fixMimeType(uri);
                }
                boolean isDirectory = false;
                List<SafFastItem> childItems = null;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    mimeType = "inode/directory";
                    isDirectory = true;
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(buildHierarchicalDocumentsUri(uri), documentId);
                    childItems = list(childUri);
                }
                return new SafFastItem(
                        DocumentsContract.buildDocumentUriUsingTree(buildHierarchicalDocumentsUri(uri), documentId),
                        cursor.getString(1),
                        mimeType,
                        cursor.getLong(3),
                        cursor.getLong(4),
                        isDirectory,
                        childItems
                );
            }
        } catch (IllegalArgumentException e) {
            throw new ItemNotFoundException(e);
        } catch (Exception e) {
            Log.w("DocumentsContractAccess", "Failed query: " + e);
            throw new FileAccessError(e);
        }
        throw new ItemNotFoundException();
    }

    private String fixMimeType(Uri uri) {
        // Ensure that seafile libraries are handled like a folder.
        // On the initial query, DocumentFile.isDirectory() returns false.
        String mimeType = "application/octet-stream";
        if ("com.seafile.seadroid2".equals(uri.getAuthority())) {
            DocumentFile file = DocumentFile.fromTreeUri(context, buildHierarchicalDocumentsUri(uri));
            if (null != file && !file.isFile()) {
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR;
            }
        }
        return mimeType;
    }

    @Override
    public void writeFile(Uri documentUri, InputStream stream, long len) {
        if (len < 0) {
            throw new IllegalArgumentException("Negative write length");
        }
        // Write directly through the ContentResolver with a 64 KB buffer instead of delegating
        // to SafFileAccess, avoiding an extra indirection and a much smaller copy buffer.
        // Use "wt" so overwriting an existing file truncates any stale trailing bytes on API 29+,
        // falling back to "w" for SAF providers that reject truncate mode.
        try (OutputStream os = openOutputStreamTruncating(documentUri)) {
            if (os == null) {
                throw new FileAccessError();
            }
            copyBounded(stream, os, len);
            os.flush();
        } catch (FileNotFoundException e) {
            throw new ItemNotFoundException(e);
        } catch (IOException e) {
            throw new FileAccessError(e);
        }
    }

    private OutputStream openOutputStreamTruncating(Uri documentUri) throws FileNotFoundException {
        try {
            return context.getContentResolver().openOutputStream(documentUri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return context.getContentResolver().openOutputStream(documentUri, "w");
        }
    }

    @androidx.annotation.VisibleForTesting
    public static long copyBounded(InputStream stream, OutputStream os, long len) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = len;
        long totalWritten = 0L;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int blen = stream.read(buffer, 0, toRead);
            if (blen == -1) {
                break;
            }
            os.write(buffer, 0, blen);
            remaining -= blen;
            totalWritten += blen;
        }
        return totalWritten;
    }

    private Uri getParent(Uri uri) {
        return buildHierarchicalDocumentsUri(uri, true);
    }

    private Uri buildHierarchicalDocumentsUri(Uri uri) {
        return buildHierarchicalDocumentsUri(uri, false);
    }

    // Note: this is modelled to observed behavior, because the
    // DocumentsContract#fromXXUri APIs don't really work well
    private Uri buildHierarchicalDocumentsUri(Uri uri, boolean buildParent) {
        if (!ProviderPaths.ANDROID_PROVIDER_AUTHORITY.equals(uri.getAuthority())) {
            if (buildParent) {
                DocumentFile doc = DocumentFile.fromTreeUri(context, uri);
                DocumentFile parent = doc != null ? doc.getParentFile() : null;
                if (parent == null) {
                    throw new ItemNotFoundException();
                }
                return parent.getUri();
            } else {
                // This is a thirdparty provider. No assumptions regarding URL
                // structure are possible. Instead, follow documented semantics.
                return treeWalkCompat(uri);
            }
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 2 || !"tree".equals(segments.get(0))) {
            throw new ItemNotFoundException();
        }
        String rootId = segments.get(1);
        if (buildParent && segments.size() == 2) {
            throw new ItemNotFoundException();
        }
        int end = segments.size() - (buildParent ? 1 : 0);
        StringBuilder documentId = new StringBuilder(rootId);
        for (int i = 2; i < end; i++) {
            if (i > 2 || !rootId.endsWith(":")) documentId.append('/');
            documentId.append(segments.get(i));
        }
        return DocumentsContract.buildDocumentUriUsingTree(uri, documentId.toString());
    }

    // /tree/primary:DCIM
    // /tree/primary:DCIM/IMG_1.jpg
    private String getDisplayName(Uri uri) {
        List<String> segments = uri.getPathSegments();
        String displayName;
        String path = uri.getPath();
        int rootSep;
        if (segments.size() >= 3) {
            displayName = segments.get(segments.size() - 1);
        } else if ((rootSep = path.indexOf(':', 6)) != -1 || (rootSep = path.indexOf('/', 6)) != -1) {
            displayName = path.substring(rootSep + 1);
        } else {
            displayName = segments.get(segments.size() - 1);
        }

        if (displayName.lastIndexOf('/') == displayName.length() - 1) {
            displayName = displayName.substring(0, displayName.length() - 1);
        }

        return displayName;
    }

    /**
     * Get the display name, using standard contract APIs.
     * @param uri document uri
     * @return display name
     */
    private String getDisplayNameCompat(Uri uri) {
        String[] projection = new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    @androidx.annotation.VisibleForTesting
    public interface SegmentLookup<T> {
        List<SafFastItem> listChildren(T folder);
    }

    private Uri treeWalkCompat(Uri uri) {
        Uri baseUri = DocumentsContract.buildTreeDocumentUri(uri.getAuthority(), DocumentsContract.getTreeDocumentId(uri));
        DocumentFile rootDoc = DocumentFile.fromTreeUri(context, baseUri);
        if (rootDoc == null) {
            throw new ItemNotFoundException();
        }
        Uri subFolder = rootDoc.getUri();
        String path = uri.getPath();
        String basePath = baseUri.getPath();
        if (path == null || basePath == null || path.length() < basePath.length()) {
            throw new ItemNotFoundException();
        }
        String mediaPath = path.substring(basePath.length());
        return resolveTreeWalkSegments(subFolder, mediaPath, this::list, SafFastItem::getUri);
    }

    @androidx.annotation.VisibleForTesting
    public interface NodeMapper<T> {
        T map(SafFastItem item);
    }

    @androidx.annotation.VisibleForTesting
    @androidx.annotation.Nullable
    public static SafFastItem findDirectChildByName(List<SafFastItem> items, String name) {
        if (items == null || name == null) {
            return null;
        }
        for (SafFastItem item : items) {
            if (name.equals(item.getName())) {
                return item;
            }
        }
        return null;
    }

    @androidx.annotation.VisibleForTesting
    public static <T> T resolveTreeWalkSegments(
            T rootNode,
            String mediaPath,
            SegmentLookup<T> lookup,
            NodeMapper<T> mapper) {
        if (rootNode == null || mediaPath == null) {
            throw new ItemNotFoundException();
        }
        T current = rootNode;
        for (String directory : mediaPath.split("/")) {
            if (directory.isEmpty()) {
                continue;
            }
            SafFastItem matched = findDirectChildByName(lookup.listChildren(current), directory);
            if (matched == null) {
                throw new ItemNotFoundException();
            }
            current = mapper.map(matched);
        }
        return current;
    }
}
