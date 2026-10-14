package ai.storyteller.photocraft.android;

import android.app.NativeActivity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Native PhotoCraft host: fullscreen canvas and Android document provider imports. */
public class PhotoCraftActivity extends NativeActivity {
    private static final int PICK_IMAGES = 1001;
    private static final int PICK_EDITOR = 1002;
    private static final int SAVE_EDITOR = 1003;
    private final Handler bridgeHandler = new Handler(Looper.getMainLooper());
    private String activeDialog = null;
    private final Runnable bridgePoll = new Runnable() {
        @Override public void run() {
            File request = new File(getFilesDir(), "dialog-bridge/request");
            if (activeDialog == null && request.exists()) {
                try {
                    activeDialog = new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8).trim();
                    request.delete();
                    Intent intent;
                    if (activeDialog.equals("save")) {
                        intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/octet-stream");
                        intent.putExtra(Intent.EXTRA_TITLE, "PhotoCraft.png");
                        startActivityForResult(intent, SAVE_EDITOR);
                    } else {
                        intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, activeDialog.equals("open-multiple"));
                        startActivityForResult(intent, PICK_EDITOR);
                    }
                } catch (Exception e) {
                    android.util.Log.e("PhotoCraft", "Cannot open picker", e);
                    answerBridge("");
                }
            }
            bridgeHandler.postDelayed(this, 250);
        }
    };
    private void answerBridge(String paths) {
        try {
            File folder = new File(getFilesDir(), "dialog-bridge");
            folder.mkdirs();
            Files.write(new File(folder, "response").toPath(), paths.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { android.util.Log.e("PhotoCraft", "Cannot answer picker", e); }
        activeDialog = null;
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        immersive();
        requestPhotoPermission();
        bridgeHandler.postDelayed(bridgePoll, 250);
        Button open = new Button(this);
        open.setText("Abrir foto");
        open.setTextSize(11);
        open.setAllCaps(false);
        open.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/octet-stream", "image/vnd.adobe.photoshop"});
            startActivityForResult(intent, PICK_IMAGES);
        });
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(130, 48, Gravity.TOP | Gravity.RIGHT);
        layout.topMargin = 3;
        layout.rightMargin = 8;
        addContentView(open, layout);
    }
    private void requestPhotoPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 2001);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 2001);
            }
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_EDITOR) {
            if (result != RESULT_OK || data == null) { answerBridge(""); return; }
            ArrayList<String> paths = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    String path = copyPicked(data.getClipData().getItemAt(i).getUri());
                    if (path != null) paths.add(path);
                }
            } else if (data.getData() != null) {
                String path = copyPicked(data.getData());
                if (path != null) paths.add(path);
            }
            answerBridge(String.join("\n", paths));
            return;
        }
        if (request == SAVE_EDITOR) {
            // Saving requires a second stage to copy the generated bytes into the chosen URI.
            answerBridge("");
            return;
        }
        if (request != PICK_IMAGES || result != RESULT_OK || data == null) return;
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                copyIncoming(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            copyIncoming(data.getData());
        }
    }
    private String copyPicked(Uri uri) {
        String name = "document";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {}
        if (name == null) name = "document";
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File dir = new File(getFilesDir(), "picked");
        dir.mkdirs();
        File file = new File(dir, System.currentTimeMillis() + "_" + name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) return null;
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return file.getAbsolutePath();
        } catch (Exception e) {
            file.delete();
            android.util.Log.e("PhotoCraft", "Cannot read selected document", e);
            return null;
        }
    }
    @Override protected void onDestroy() {
        bridgeHandler.removeCallbacks(bridgePoll);
        super.onDestroy();
    }
    private void copyIncoming(Uri uri) {
        String name = "image";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) name = "image";
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.equals(".") || name.equals("..")) name = "image";
        File dir = new File(getFilesDir(), "incoming");
        if (!dir.exists() && !dir.mkdirs()) return;
        File target = new File(dir, System.currentTimeMillis() + "_" + name);
        File partial = new File(target.getAbsolutePath() + ".part");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(partial)) {
            if (in == null) return;
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            if (!partial.renameTo(target)) partial.delete();
        } catch (Exception e) {
            partial.delete();
            android.util.Log.e("PhotoCraft", "Cannot import image", e);
        }
    }
}
