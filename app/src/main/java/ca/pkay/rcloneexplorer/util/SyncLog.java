package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Copyright (C) 2021  Felix Nüsse
 * Created on 20.06.21 - 15:30
 *
 * Edited by: Felix Nüsse felix.nuesse(at)t-online.de
 *
 */

public class SyncLog {

    private static int loglength = 4;

    public static String TIMESTAMP = "timestamp";
    public static String TITLE = "title";
    public static String CONTENT = "content";
    public static String TYPE = "type";
    public static final int TYPE_ERROR = 0;
    public static final int TYPE_INFO = 1;

    public static synchronized ArrayList<JSONObject> getLog(Context c){
        File log = new File(c.getFilesDir().getPath() + "/sync.log");
        ArrayList<JSONObject> jsons = new ArrayList<>();
        if (!log.exists()) return jsons;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try { jsons.add(new JSONObject(line)); } catch (JSONException ignored) { }
            }
        } catch (IOException e) {
            Log.e("SyncLog", "Could not read logs", e);
        }
        Collections.reverse(jsons);
        return jsons;
    }

    private static synchronized void appendLog(Context c, String entry){

        File log = new File(c.getFilesDir().getPath() + "/sync.log");
        try {
            FileWriter writer = new FileWriter(log, true);
            writer.append(System.lineSeparator());
            writer.append(entry);
            writer.flush();
            writer.close();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public static long log(Context c, String title, String content, int type){
        JSONObject json = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            json.put(TIMESTAMP, now);
            json.put(CONTENT, content);
            json.put(TITLE, title);
            json.put(TYPE, type);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        appendLog(c, json.toString());
        return now;
    }

    public static long error(Context c, String title, String content){
        return log(c, title, content, TYPE_ERROR);
    }

    public static long info(Context c, String title, String content){
        return log(c, title, content, TYPE_INFO);
    }

    public static synchronized void appendLegacy(Context c, String message) {
        File directory = new File(c.getFilesDir(), "logs");
        if (!directory.exists() && !directory.mkdirs()) return;
        File log = new File(directory, "log.txt");
        if (log.length() / 1024 > 10000 && !log.delete()) {
            Log.w("SyncLog", "Could not rotate legacy log");
        }
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        try (FileWriter writer = new FileWriter(log, true)) {
            writer.append(timestamp).append(" - ").append(message).append('\n');
        } catch (IOException e) { Log.e("SyncLog", "Could not write legacy log", e); }
    }

    public static synchronized boolean hasLogFiles(Context c) { return new File(c.getFilesDir(), "sync.log").length() > 0 || new File(c.getFilesDir(), "logs/log.txt").length() > 0 || new File(c.getFilesDir(), "logs/rcd.log").length() > 0 || new File(c.getFilesDir(), "logs/serve.log").length() > 0; }

    public static synchronized boolean delete(Context c){
        File log = new File(c.getFilesDir(), "sync.log");
        File legacy = new File(c.getFilesDir(), "logs/log.txt");
        boolean first = !log.exists() || log.delete();
        boolean second = !legacy.exists() || legacy.delete();
        boolean third = true;
        // Running rclone processes may hold these files open; preserve their inodes.
        for (String name : new String[]{"logs/rcd.log", "logs/serve.log"}) {
            File activeLog = new File(c.getFilesDir(), name);
            if (activeLog.exists()) {
                try (java.io.FileOutputStream ignored = new java.io.FileOutputStream(activeLog, false)) {
                    // Opening without append truncates the file.
                } catch (IOException e) { Log.e("SyncLog", "Could not clear active log", e); third = false; }
            }
        }
        return first && second && third;
    }
}
