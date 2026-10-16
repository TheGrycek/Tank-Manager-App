package com.example.tank_manager;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * History samples downloaded from the controller, kept without a limit (a year is about 0.5 MB).
 * Samples are keyed by their time, so a sample received twice is stored once. Also remembers the
 * number of the next sample to ask the controller for.
 */
final class HistoryStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "history.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "samples";
    private static final String PREFS = "history";
    private static final String KEY_NEXT_SEQ = "next_seq";

    private static HistoryStore instance;

    private final SharedPreferences prefs;

    /** The one instance of the app (the service writes, the screen reads). */
    static synchronized HistoryStore get(Context context) {
        if (instance == null) {
            instance = new HistoryStore(context);
        }
        return instance;
    }

    private HistoryStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (time INTEGER PRIMARY KEY, gray INTEGER NOT NULL,"
                + " clean INTEGER NOT NULL, temperature REAL, moving INTEGER NOT NULL DEFAULT 0,"
                + " tilted INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Driving and tilt flags; older samples count as steady
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN moving INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN tilted INTEGER NOT NULL DEFAULT 0");
        }
    }

    /** Stores the dated samples; returns how many there were. */
    int add(List<HistorySample> samples) {
        SQLiteDatabase db = getWritableDatabase();
        int stored = 0;
        db.beginTransaction();
        try {
            ContentValues row = new ContentValues();
            for (HistorySample s : samples) {
                if (s.time < 0) {
                    continue; // taken before the controller knew the date
                }
                row.clear();
                row.put("time", s.time);
                row.put("gray", s.gray);
                row.put("clean", s.clean);
                if (s.temperature == null) {
                    row.putNull("temperature");
                } else {
                    row.put("temperature", s.temperature);
                }
                row.put("moving", s.moving ? 1 : 0);
                row.put("tilted", s.tilted ? 1 : 0);
                db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE);
                stored++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return stored;
    }

    /** Samples from the given Unix time on, oldest first. */
    List<HistorySample> since(long fromTime) {
        List<HistorySample> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, new String[]{"time", "gray", "clean", "temperature", "moving", "tilted"},
                "time >= ?", new String[]{Long.toString(fromTime)}, null, null, "time")) {
            while (c.moveToNext()) {
                out.add(new HistorySample(c.getLong(0), c.getInt(1), c.getInt(2),
                        c.isNull(3) ? null : c.getFloat(3), c.getInt(4) != 0, c.getInt(5) != 0));
            }
        }
        return out;
    }

    long nextSeq() {
        return prefs.getLong(KEY_NEXT_SEQ, 0);
    }

    void setNextSeq(long seq) {
        prefs.edit().putLong(KEY_NEXT_SEQ, seq).apply();
    }
}
