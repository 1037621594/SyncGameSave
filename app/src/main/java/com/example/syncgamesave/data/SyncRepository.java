package com.example.syncgamesave.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.example.syncgamesave.model.FileState;
import com.example.syncgamesave.model.PathMapping;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;

public class SyncRepository extends SQLiteOpenHelper {
    private final Gson gson = new Gson();

    public SyncRepository(Context context) {
        super(context.getApplicationContext(), "sync.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE mappings (id TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE files (mapping_id TEXT NOT NULL, path TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(mapping_id,path))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("数据库版本暂不支持升级");
    }

    public synchronized List<PathMapping> mappings() {
        List<PathMapping> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("mappings", new String[]{"payload"}, null, null, null, null, "rowid ASC")) {
            while (cursor.moveToNext()) result.add(gson.fromJson(cursor.getString(0), PathMapping.class));
        }
        return result;
    }

    public synchronized void saveMapping(PathMapping mapping) {
        ContentValues values = new ContentValues();
        values.put("id", mapping.getId());
        values.put("payload", gson.toJson(mapping));
        SQLiteDatabase db = getWritableDatabase();
        if (db.update("mappings", values, "id=?", new String[]{mapping.getId()}) == 0)
            db.insertOrThrow("mappings", null, values);
    }

    public synchronized void saveAndReset(PathMapping mapping, boolean reset) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (reset) {
                clearFiles(mapping.getId());
                mapping.setLastUploadTime(0);
                mapping.setLastResult("范围已更新，等待首次上传");
            }
            saveMapping(mapping);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized FileState file(String mappingId, String path) {
        try (Cursor cursor = getReadableDatabase().query("files", new String[]{"payload"},
                "mapping_id=? AND path=?", new String[]{mappingId, path}, null, null, null)) {
            return cursor.moveToFirst() ? gson.fromJson(cursor.getString(0), FileState.class) : null;
        }
    }

    public synchronized void saveFile(String mappingId, FileState state) {
        ContentValues values = new ContentValues();
        values.put("mapping_id", mappingId);
        values.put("path", state.getRelativePath());
        values.put("payload", gson.toJson(state));
        if (getWritableDatabase().insertWithOnConflict("files", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new android.database.sqlite.SQLiteException("无法保存文件同步记录");
    }

    public synchronized void clearFiles(String id) {
        getWritableDatabase().delete("files", "mapping_id=?", new String[]{id});
    }

    public synchronized void deleteMapping(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            clearFiles(id);
            db.delete("mappings", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void resetAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("files", null, null);
            for (PathMapping mapping : mappings()) {
                mapping.setLastUploadTime(0);
                mapping.setLastResult("存储目标已变更，等待重新上传");
                saveMapping(mapping);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void recoverInterrupted() {
        for (PathMapping mapping : mappings()) {
            if (mapping.isInterrupted()) {
                mapping.setInterrupted(false);
                mapping.setLastResult("上次任务未完成，请重试");
                saveMapping(mapping);
            }
        }
    }
}

