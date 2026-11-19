package com.docsach.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Library sync through a folder the user picks ONCE with the system
 * folder picker — typically a folder inside Google Drive, OneDrive or
 * MEGA on this phone, whose own app then syncs it to the cloud with
 * the account already signed in on the device. The app keeps a
 * persistable URI permission, so after that one pick it can read and
 * write the library backup file with no further interaction, no
 * account password and no third-party registration.
 *
 * The synced payload is the page's library backup JSON
 * (doc-sach-thu-vien.json): every book's text plus reading positions.
 */
public final class SyncStore {

    public static final String FILE_NAME = "doc-sach-thu-vien.json";

    private static final String PREFS = "docsach";
    private static final String KEY_TREE = "syncTreeUri";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile int lastWrittenHash = 0;
    private static volatile boolean hasWrittenHash = false;

    public interface Status {
        void onStatus(String message);
    }

    public interface ReadCallback {
        /** json is null when the folder holds no backup yet. */
        void onRead(String json, long lastModified);
    }

    private SyncStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasFolder(Context c) {
        return prefs(c).getString(KEY_TREE, null) != null;
    }

    private static Uri treeUri(Context c) {
        String s = prefs(c).getString(KEY_TREE, null);
        return s != null ? Uri.parse(s) : null;
    }

    public static Intent pickFolderIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    /** Stores the picked folder and takes the persistable permission.
     *  Returns false when the grant could not be taken. */
    public static boolean acceptFolder(Context c, Intent data) {
        if (data == null || data.getData() == null) {
            return false;
        }
        Uri tree = data.getData();
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (flags == 0) {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        try {
            c.getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (Exception e) {
            return false;
        }
        prefs(c).edit().putString(KEY_TREE, tree.toString()).apply();
        hasWrittenHash = false;
        return true;
    }

    /** Finds our backup file directly under the picked folder.
     *  Returns {documentId, lastModifiedMillis} or null. */
    private static String[] findChild(Context c, Uri tree) {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        Cursor cursor = null;
        try {
            cursor = c.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                    null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    if (FILE_NAME.equals(cursor.getString(1))) {
                        return new String[]{cursor.getString(0),
                                String.valueOf(cursor.getLong(2))};
                    }
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /** Writes the library JSON into the sync folder (creating the file
     *  the first time). Identical content is not rewritten. Runs on a
     *  background thread; the status callback fires on that thread. */
    public static void writeLibrary(final Context c, final String json,
                                    final Status status) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                Uri tree = treeUri(c);
                if (tree == null) {
                    return;
                }
                int hash = json.hashCode();
                if (hasWrittenHash && hash == lastWrittenHash) {
                    return;
                }
                try {
                    String[] child = findChild(c, tree);
                    Uri doc;
                    if (child != null) {
                        doc = DocumentsContract.buildDocumentUriUsingTree(tree, child[0]);
                    } else {
                        String rootId = DocumentsContract.getTreeDocumentId(tree);
                        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
                        doc = DocumentsContract.createDocument(
                                c.getContentResolver(), parent, "application/json", FILE_NAME);
                    }
                    if (doc == null) {
                        if (status != null) {
                            status.onStatus("Không ghi được file đồng bộ vào thư mục đã chọn.");
                        }
                        return;
                    }
                    OutputStream out = null;
                    try {
                        out = c.getContentResolver().openOutputStream(doc, "wt");
                        if (out == null) {
                            out = c.getContentResolver().openOutputStream(doc, "w");
                        }
                        if (out == null) {
                            throw new IllegalStateException("no stream");
                        }
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } finally {
                        if (out != null) {
                            out.close();
                        }
                    }
                    lastWrittenHash = hash;
                    hasWrittenHash = true;
                    if (status != null) {
                        status.onStatus("Đã đồng bộ thư viện vào thư mục trên máy.");
                    }
                } catch (Exception e) {
                    if (status != null) {
                        status.onStatus("Đồng bộ vào thư mục lỗi — kiểm tra ứng dụng Drive/OneDrive/MEGA trên máy.");
                    }
                }
            }
        });
    }

    /** Reads the backup from the sync folder, if one exists. */
    public static void readLibrary(final Context c, final ReadCallback callback) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                Uri tree = treeUri(c);
                if (tree == null) {
                    callback.onRead(null, 0);
                    return;
                }
                try {
                    String[] child = findChild(c, tree);
                    if (child == null) {
                        callback.onRead(null, 0);
                        return;
                    }
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, child[0]);
                    InputStream in = c.getContentResolver().openInputStream(doc);
                    if (in == null) {
                        callback.onRead(null, 0);
                        return;
                    }
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try {
                        byte[] chunk = new byte[16384];
                        int n;
                        while ((n = in.read(chunk)) != -1) {
                            buffer.write(chunk, 0, n);
                        }
                    } finally {
                        in.close();
                    }
                    long modified = 0;
                    try {
                        modified = Long.parseLong(child[1]);
                    } catch (Exception ignored) {
                        // Timestamp is advisory only.
                    }
                    callback.onRead(new String(buffer.toByteArray(), StandardCharsets.UTF_8),
                            modified);
                } catch (Exception e) {
                    callback.onRead(null, 0);
                }
            }
        });
    }
}
