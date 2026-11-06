package com.radar.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/** Cámara real con vista previa. Solo está encendida con esta pantalla abierta (indicador rojo visible).
 *  La foto se saca cuando pulsas; analizarla o guardarla en la galería lo decides tú. */
@SuppressWarnings("deprecation")
public class CameraActivity extends Activity implements SurfaceHolder.Callback {
    private Camera cam;
    private int facing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private SurfaceView sv;
    private ImageView photo;
    private LinearLayout preview, after;
    private TextView info;
    private byte[] jpeg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Perms.granted(this, "android.permission.CAMERA")) {
            JarvisApp.store(this).flag("asked_camera", true);
            requestPermissions(new String[]{"android.permission.CAMERA"}, 1);
        }
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 12);
        root.setPadding(pad, pad, pad, pad);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.mono(this, "● CÁMARA ACTIVA", 12, Ui.BAD));
        info = Ui.mono(this, "", 11, Ui.DIM);
        head.addView(info, Ui.margins(this, 12, 0, 0, 0));
        root.addView(head);
        FrameLayout stage = new FrameLayout(this);
        sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        stage.addView(sv);
        photo = new ImageView(this);
        photo.setVisibility(View.GONE);
        photo.setBackgroundColor(Ui.BG);
        stage.addView(photo);
        root.addView(stage, Ui.lp(-1, 0, 1));
        preview = Ui.row(this);
        preview.addView(Ui.button(this, "Capturar", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) { capture(); } }));
        preview.addView(space());
        preview.addView(Ui.button(this, "Cambiar cámara", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { switchCam(); } }));
        preview.addView(space());
        preview.addView(Ui.button(this, "Cerrar", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { finish(); } }));
        root.addView(preview, Ui.margins(this, 0, 10, 0, 0));
        after = Ui.row(this);
        after.setVisibility(View.GONE);
        after.addView(Ui.button(this, "¿Qué ves?", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) { useForVision(); } }));
        after.addView(space());
        after.addView(Ui.button(this, "Guardar…", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { saveToGallery(); } }));
        after.addView(space());
        after.addView(Ui.button(this, "Descartar", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { discard(); } }));
        root.addView(after, Ui.margins(this, 0, 10, 0, 0));
        setContentView(root);
    }

    private View space() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 6), 1)); return v; }

    private void open() {
        close();
        if (!Perms.granted(this, "android.permission.CAMERA")) return;
        if (JarvisApp.brainOrNull() != null && JarvisApp.brainOrNull().isPaused()) { Toast.makeText(this, "JARVIS está en pausa.", Toast.LENGTH_LONG).show(); finish(); return; }
        try {
            int id = 0, n = Camera.getNumberOfCameras();
            Camera.CameraInfo ci = new Camera.CameraInfo();
            for (int i = 0; i < n; i++) { Camera.getCameraInfo(i, ci); if (ci.facing == facing) { id = i; break; } }
            cam = Camera.open(id);
            cam.setDisplayOrientation(90);
            cam.setPreviewDisplay(sv.getHolder());
            cam.startPreview();
            info.setText((facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "frontal" : "trasera") + " · " + n + " cámara" + (n == 1 ? "" : "s"));
            log("cámara abierta (" + (facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "frontal" : "trasera") + ")");
        } catch (Exception e) {
            Toast.makeText(this, "No pude abrir la cámara: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void close() {
        if (cam != null) { try { cam.stopPreview(); } catch (Exception ignored) {} cam.release(); cam = null; log("cámara cerrada"); }
    }

    private void switchCam() {
        facing = facing == Camera.CameraInfo.CAMERA_FACING_BACK ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
        if (Camera.getNumberOfCameras() < 2) Toast.makeText(this, "Este teléfono solo tiene una cámara.", Toast.LENGTH_SHORT).show();
        open();
    }

    private void capture() {
        if (cam == null) return;
        cam.takePicture(null, null, new Camera.PictureCallback() {
            public void onPictureTaken(byte[] data, Camera c) {
                Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                Matrix m = new Matrix();
                m.postRotate(facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? 270 : 90);
                bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                jpeg = MainActivity.jpegSmall(bm);
                photo.setImageBitmap(bm);
                photo.setVisibility(View.VISIBLE);
                preview.setVisibility(View.GONE);
                after.setVisibility(View.VISIBLE);
                close();  // con la foto hecha, la cámara se apaga
                log("foto capturada (no guardada)");
            }
        });
    }

    private void useForVision() {
        try {
            File f = new File(getCacheDir(), "foto.jpg");
            FileOutputStream o = new FileOutputStream(f);
            o.write(jpeg);
            o.close();
            setResult(RESULT_OK, new Intent().putExtra("jpeg", f.getAbsolutePath()));
        } catch (Exception e) {
            Toast.makeText(this, "No pude preparar la foto.", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void saveToGallery() {
        new AlertDialog.Builder(this).setMessage("¿Guardar esta foto en la galería del teléfono?")
                .setPositiveButton("Guardar", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    String r = MediaStore.Images.Media.insertImage(getContentResolver(), BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length), "JARVIS", "Foto de JARVIS");
                    Toast.makeText(CameraActivity.this, r != null ? "Guardada en la galería." : "No se pudo guardar (Android puede pedir permiso de almacenamiento).", Toast.LENGTH_LONG).show();
                    if (r != null) log("foto guardada por el usuario");
                }}).setNegativeButton("Cancelar", null).show();
    }

    private void discard() {
        jpeg = null;
        photo.setVisibility(View.GONE);
        after.setVisibility(View.GONE);
        preview.setVisibility(View.VISIBLE);
        log("foto descartada");
        open();
    }

    private void log(String s) { if (JarvisApp.brainOrNull() != null) JarvisApp.brainOrNull().log("camara", s); }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        if (res.length > 0 && res[0] == 0) open();
        else { Toast.makeText(this, "Sin permiso de cámara. Ajustes → Aplicaciones → JARVIS → Permisos → Cámara.", Toast.LENGTH_LONG).show(); finish(); }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { if (jpeg == null) open(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { close(); }
    @Override protected void onPause() { super.onPause(); close(); }  // nunca en segundo plano
}
