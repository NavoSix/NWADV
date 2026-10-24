package com.northwoodsadv.ride.android;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.database.Cursor;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.northwoodsadv.ride.NwadvGame;
import com.northwoodsadv.ride.Platform;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

public class AndroidLauncher extends AndroidApplication implements Platform {
    private static final int PICK_GPX = 4242;
    private Function2<? super String, ? super String, Unit> pickCallback;
    private volatile Pair<String, String> pendingGpx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = true;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useImmersiveMode = true;
        config.numSamples = 2;
        config.depth = 24;
        handleIntent(getIntent());
        initialize(new NwadvGame(this, null, false), config);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** GPX handed to us with "Open with" or "Share". */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        try {
            pendingGpx = new Pair<>(displayName(uri), readText(uri));
        } catch (Exception e) {
            pendingGpx = null;
        }
    }

    @Override
    public Pair<String, String> takePendingGpx() {
        Pair<String, String> p = pendingGpx;
        pendingGpx = null;
        return p;
    }

    @Override
    public File getMapCacheDir() {
        File f = new File(getFilesDir(), "mapcache");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    @Override
    public boolean isTouchDevice() {
        return true;
    }

    @Override
    public void pickGpxFile(Function2<? super String, ? super String, Unit> onResult) {
        pickCallback = onResult;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // GPX has no universally registered MIME type, so accept anything and validate on load.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/gpx+xml", "application/octet-stream", "text/xml", "application/xml", "*/*"});
        try {
            startActivityForResult(intent, PICK_GPX);
        } catch (Exception e) {
            deliver(null, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GPX) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            deliver(null, null);
            return;
        }
        Uri uri = data.getData();
        try {
            deliver(displayName(uri), readText(uri));
        } catch (Exception e) {
            deliver(null, null);
        }
    }

    private void deliver(final String name, final String text) {
        final Function2<? super String, ? super String, Unit> cb = pickCallback;
        pickCallback = null;
        if (cb == null) return;
        Gdx.app.postRunnable(() -> cb.invoke(name, text));
    }

    @Override
    public void vibrate(int millis) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
        else v.vibrate(millis);
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Can't open " + uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "route.gpx";
    }
}
