package pl.micekwidget;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateApkProvider extends ContentProvider {
    private static final String FILE = "MicekApp-update.apk";

    static File file(android.content.Context context) { return new File(context.getCacheDir(), FILE); }

    static Uri uri(android.content.Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".updates/" + FILE);
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return valid(uri) ? "application/vnd.android.package-archive" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!valid(uri) || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!valid(uri)) return null;
        String[] fields = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(fields);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String field : fields) row.add(OpenableColumns.DISPLAY_NAME.equals(field) ? FILE
                : OpenableColumns.SIZE.equals(field) ? file(getContext()).length() : null);
        return cursor;
    }

    private boolean valid(Uri uri) { return uri(getContext()).equals(uri); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String select, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String select, String[] args) { throw new UnsupportedOperationException(); }
}
