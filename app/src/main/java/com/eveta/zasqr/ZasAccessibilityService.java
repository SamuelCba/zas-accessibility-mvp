package com.eveta.zasqr;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ZasAccessibilityService extends AccessibilityService {

    private static final String TAG         = "ZasQRG";
    private static final String ZAS_PACKAGE = "bec.vdb.direct";
    private static final long   ACTION_DELAY_MS  = 900L;
    private static final long   POLL_INTERVAL_MS = 3000L;
    static final String         ACTION_HISTORY_UPDATED = "com.eveta.zasqr.HISTORY_UPDATED";

    private static ZasAccessibilityService instance;

    private enum State {
        IDLE, EDITING, FILLING, CONFIGURING, GENERATING,
        WAITING_FOR_SAVE, DECODING, SAVING, RESETTING
    }

    private State   state = State.IDLE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String  amt = "5.00";
    private String  ref = "PAGO_AUTO";
    private boolean validitySelected;
    private boolean singleUseEnabled;
    private boolean amountFieldPrepared;
    private boolean keyboardDismissed;
    private long    nextActionAtMs;

    private BackendClient.TopupData currentTopup;
    // Max MediaStore _ID of QR images seen before we started generating
    private long preGenMaxQrId = -1L;

    private WindowManager windowManager;
    private TextView      statusPopup;
    private final Runnable hidePopupRunnable = this::hidePopup;
    private final Runnable retryRunnable     = this::retryProcess;
    private final Runnable pollRunnable      = this::doPoll;

    public static ZasAccessibilityService getInstance() { return instance; }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showStatus("ZAS QRG conectado");
        schedulePoll(POLL_INTERVAL_MS);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hidePopup();
        mainHandler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onInterrupt() {}

    // ─── Backend polling ─────────────────────────────────────────────────────

    private void schedulePoll(long delayMs) {
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.postDelayed(pollRunnable, delayMs);
    }

    private void doPoll() {
        if (state != State.IDLE) { schedulePoll(POLL_INTERVAL_MS); return; }
        String token = BackendClient.token(this);
        if (token.isEmpty()) { schedulePoll(POLL_INTERVAL_MS); return; }

        new Thread(() -> {
            try {
                BackendClient.TopupData topup = BackendClient.getNextTopup(this);
                mainHandler.post(() -> {
                    if (topup == null || state != State.IDLE) {
                        schedulePoll(POLL_INTERVAL_MS);
                        return;
                    }
                    // Skip if already processed (dedup)
                    if (HistoryStorage.alreadyProcessed(this, topup.id)) {
                        Log.w(TAG, "Topup " + topup.id + " ya procesado, saltando");
                        schedulePoll(POLL_INTERVAL_MS);
                        return;
                    }
                    currentTopup = topup;
                    startAutoFlow(topup.amount, topup.reference);
                });
            } catch (Exception e) {
                Log.w(TAG, "poll error: " + e.getMessage());
                mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            }
        }).start();
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public void startAutoFlow(String amt, String ref) {
        mainHandler.removeCallbacks(pollRunnable);
        resetFlowState(amt, ref);
        state = State.EDITING;
        showStatus("Iniciando: " + amt + " / " + ref);
        Intent i = getPackageManager().getLaunchIntentForPackage(ZAS_PACKAGE);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(ZAS_PACKAGE, ZAS_PACKAGE + ".MainActivity");
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    // ─── Accessibility events ────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (pkg.equals(ZAS_PACKAGE) && state != State.IDLE && shouldProcessEvent(event.getEventType())) {
            process(getRootInActiveWindow());
        }
    }

    private boolean shouldProcessEvent(int t) {
        return t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            || t == AccessibilityEvent.TYPE_VIEW_CLICKED
            || t == AccessibilityEvent.TYPE_VIEW_SCROLLED
            || t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
    }

    // ─── State machine ───────────────────────────────────────────────────────

    private void process(AccessibilityNodeInfo root) {
        if (root == null) return;
        if (System.currentTimeMillis() < nextActionAtMs) return;

        switch (state) {
            case EDITING:
                if (openEditor(root)) advance(State.FILLING, ACTION_DELAY_MS, "Abriendo formulario");
                break;
            case FILLING:
                if (fillForm(root)) advance(State.CONFIGURING, ACTION_DELAY_MS, "Monto y motivo OK");
                break;
            case CONFIGURING:
                if (configureForm(root)) advance(State.GENERATING, ACTION_DELAY_MS, "Config OK");
                break;
            case GENERATING:
                // Snapshot max QR _ID right before clicking Generar
                preGenMaxQrId = getMaxQrId();
                if (clickByLabels(root, "Generar")) {
                    advance(State.WAITING_FOR_SAVE, 1500L, "Generando QR...");
                }
                break;
            case WAITING_FOR_SAVE:
                if (saveResult(root)) advance(State.DECODING, ACTION_DELAY_MS, "Guardando imagen...");
                break;
            case DECODING:
                // background thread handles this
                break;
            case SAVING:
                if (openEditor(root)) advance(State.RESETTING, ACTION_DELAY_MS, "Limpiando...");
                break;
            case RESETTING:
                if (clickByLabels(root, "Limpiar")) {
                    showStatus("Ciclo completo");
                    state = State.IDLE;
                    schedulePoll(POLL_INTERVAL_MS);
                }
                break;
        }
    }

    private void advance(State newState, long delayMs, String status) {
        state = newState;
        nextActionAtMs = System.currentTimeMillis() + delayMs;
        showStatus(status);
        if (newState == State.DECODING) {
            startDecodeAndSubmit();
        } else {
            scheduleRetry(delayMs);
        }
    }

    // ─── Decode & submit ─────────────────────────────────────────────────────

    private void startDecodeAndSubmit() {
        final long snapshotId = preGenMaxQrId;
        new Thread(() -> {
            // Wait 3s for gallery to index the saved image
            sleep(3000);

            String payload = null;
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                payload = findAndDecodeQrAfter(snapshotId);
                if (payload != null) break;
                Log.d(TAG, "QR no encontrado aun, reintentando...");
                sleep(1500);
            }

            final String qrPayload  = payload != null ? payload : "";
            final boolean hasPayload = !qrPayload.isEmpty();

            Log.i(TAG, hasPayload ? "QR decodificado: " + qrPayload : "No se decodifico QR");

            boolean submitted = false;
            if (hasPayload && currentTopup != null) {
                try {
                    submitted = BackendClient.submitQrPayload(this, currentTopup.id, qrPayload);
                    Log.i(TAG, "Submit: " + submitted);
                } catch (Exception e) {
                    Log.e(TAG, "Submit error: " + e.getMessage());
                }
            }

            if (currentTopup != null) {
                HistoryEntry entry = new HistoryEntry();
                entry.topupId     = currentTopup.id;
                entry.amount      = currentTopup.amount;
                entry.reference   = currentTopup.reference;
                entry.concept     = currentTopup.concept;
                entry.qrPayload   = qrPayload;
                entry.timestampMs = System.currentTimeMillis();
                entry.submitted   = submitted;
                HistoryStorage.add(this, entry);
                sendBroadcast(new Intent(ACTION_HISTORY_UPDATED));
            }

            final boolean ok = submitted;
            mainHandler.post(() -> {
                showStatus(ok ? "Enviado OK" : (hasPayload ? "Decode OK, submit fallo" : "Sin QR decodificado"));
                currentTopup = null;
                advance(State.SAVING, ACTION_DELAY_MS, "Volviendo al formulario");
            });
        }).start();
    }

    // ─── MediaStore + ZXing ──────────────────────────────────────────────────

    /** Returns the highest _ID among QR* images currently in the gallery, or -1 if none. */
    private long getMaxQrId() {
        String[] proj = {MediaStore.Images.Media._ID};
        String   sel  = MediaStore.Images.Media.DISPLAY_NAME + " LIKE 'QR%'";
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                MediaStore.Images.Media._ID + " DESC")) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) { Log.w(TAG, "getMaxQrId: " + e.getMessage()); }
        return -1L;
    }

    /** Finds the first QR* image with _ID > snapshotId and decodes it. */
    private String findAndDecodeQrAfter(long snapshotId) {
        String[] proj = {MediaStore.Images.Media._ID};
        String   sel  = MediaStore.Images.Media.DISPLAY_NAME + " LIKE 'QR%'"
                      + " AND " + MediaStore.Images.Media._ID + " > " + snapshotId;
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                MediaStore.Images.Media._ID + " DESC")) {
            if (c != null && c.moveToFirst()) {
                long id  = c.getLong(0);
                Uri  uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                Log.d(TAG, "QR image found _ID=" + id + " uri=" + uri);
                return decodeQrUri(uri);
            }
        } catch (Exception e) { Log.w(TAG, "findQr: " + e.getMessage()); }
        return null;
    }

    private String decodeQrUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            bmp.recycle();
            BinaryBitmap bin = new BinaryBitmap(
                    new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)));
            Result result = new QRCodeReader().decode(bin);
            return result.getText();
        } catch (Exception e) {
            Log.w(TAG, "ZXing: " + e.getMessage());
            return null;
        }
    }

    // ─── Form interaction ────────────────────────────────────────────────────

    private boolean fillForm(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo amountField = findFieldByKeywords(root,
                "monto", "importe", "amount", "valor");
        AccessibilityNodeInfo referenceField = findFieldByKeywords(root,
                "motivo", "referencia", "detalle", "descripcion", "glosa", "concepto");

        List<AccessibilityNodeInfo> fields = collectEditableFields(root);
        if (amountField == null && !fields.isEmpty()) amountField = fields.get(0);
        if (referenceField == null && fields.size() > 1)
            referenceField = fields.get(amountField == fields.get(0) ? 1 : 0);

        if (amountField == null || referenceField == null) {
            showStatus("Campos no detectados");
            return false;
        }

        if (!amountFieldPrepared) {
            amountFieldPrepared = primeAmountField(amountField);
            if (!amountFieldPrepared) {
                showStatus("No pude preparar monto");
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }
            showStatus("Campo monto listo");
            nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
            scheduleRetry(ACTION_DELAY_MS);
            return false;
        }

        if (!setText(amountField, amt)) {
            showStatus("Reintentando monto");
            nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
            return false;
        }

        sleep(250);
        boolean refOk = setText(referenceField, ref);

        if (refOk) {
            amountFieldPrepared = false;
            if (!keyboardDismissed) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                keyboardDismissed = true;
                showStatus("Cerrando teclado");
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean configureForm(AccessibilityNodeInfo root) {
        if (!validitySelected) {
            if (clickByLabels(root, "1 dia", "1 día")) {
                validitySelected = true;
                keyboardDismissed = true;
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                showStatus("Validez: 1 dia");
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }
            AccessibilityNodeInfo vc = findClickableNodeByKeywords(root, "validez", "vigencia", "vencimiento");
            if (vc != null && performClick(vc)) {
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                showStatus("Abriendo selector validez");
                scheduleRetry(ACTION_DELAY_MS);
            }
            return false;
        }

        if (!singleUseEnabled) {
            AccessibilityNodeInfo toggle = findSingleUseToggle(root);
            if (toggle == null) return false;
            if (!isChecked(toggle)) {
                if (!performClick(toggle)) return false;
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                showStatus("Activando un solo uso");
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }
            singleUseEnabled = true;
        }
        return true;
    }

    private boolean openEditor(AccessibilityNodeInfo root) {
        if (clickByLabels(root, "Editar", "Nuevo QR", "Crear QR")) return true;
        return clickBottom(root, 0);
    }

    private boolean saveResult(AccessibilityNodeInfo root) {
        if (clickByLabels(root, "Guardar")) return true;
        return clickBottom(root, 1);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void resetFlowState(String newAmt, String newRef) {
        this.amt               = sanitizeAmount(newAmt);
        this.ref               = sanitizeReference(newRef);
        this.validitySelected  = false;
        this.singleUseEnabled  = false;
        this.amountFieldPrepared = false;
        this.keyboardDismissed = false;
        this.nextActionAtMs    = 0L;
        this.preGenMaxQrId     = -1L;
        mainHandler.removeCallbacks(retryRunnable);
    }

    private boolean primeAmountField(AccessibilityNodeInfo node) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean f = node.performAction(AccessibilityNodeInfo.ACTION_CLICK); sleep(260);
        boolean s = node.performAction(AccessibilityNodeInfo.ACTION_CLICK); sleep(260);
        return f || s;
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        sleep(180);
        Bundle clear = new Bundle();
        clear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear);
        sleep(120);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        sleep(180);
        return ok || value.equals(safe(node.getText()));
    }

    private boolean clickByLabels(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            AccessibilityNodeInfo node = findLabel(root, label);
            if (node != null && performClick(node)) return true;
        }
        return false;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int d = 0; d < 5 && cur != null; d++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            cur = cur.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean clickBottom(AccessibilityNodeInfo root, int index) {
        List<AccessibilityNodeInfo> clickables = new ArrayList<>();
        findClickable(root, clickables);
        List<AccessibilityNodeInfo> bottom = new ArrayList<>();
        int minTop = (int)(getResources().getDisplayMetrics().heightPixels * 0.72f);
        for (AccessibilityNodeInfo n : clickables) if (getBounds(n).top >= minTop) bottom.add(n);
        Collections.sort(bottom, (a, b) -> {
            int t = Integer.compare(getBounds(a).top, getBounds(b).top);
            return t != 0 ? t : Integer.compare(getBounds(a).left, getBounds(b).left);
        });
        return bottom.size() > index && performClick(bottom.get(index));
    }

    private List<AccessibilityNodeInfo> collectEditableFields(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        findFields(root, fields);
        Collections.sort(fields, (a, b) -> Integer.compare(getBounds(a).top, getBounds(b).top));
        return fields;
    }

    private AccessibilityNodeInfo findFieldByKeywords(AccessibilityNodeInfo root, String... kw) {
        for (AccessibilityNodeInfo f : collectEditableFields(root)) {
            if (matchesAny(f, kw)) return f;
            AccessibilityNodeInfo p = f.getParent();
            if (matchesAny(p, kw)) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableNodeByKeywords(AccessibilityNodeInfo root, String... kw) {
        if (root == null) return null;
        if (matchesAny(root, kw) && (root.isClickable() || root.isFocusable())) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo f = findClickableNodeByKeywords(root.getChild(i), kw);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findLabel(AccessibilityNodeInfo n, String l) {
        if (n == null) return null;
        if (matchesText(n, l)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findLabel(n.getChild(i), l);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findClass(AccessibilityNodeInfo n, String cls) {
        if (n == null) return null;
        if (safe(n.getClassName()).equals(cls)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findClass(n.getChild(i), cls);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findSingleUseToggle(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo labeled = findClickableNodeByKeywords(root,
                "un solo uso", "solo uso", "pago unico", "pago único", "unico", "único");
        if (labeled != null) {
            AccessibilityNodeInfo sw = labeled;
            for (int i = 0; i < 4 && sw != null; i++) {
                String cn = safe(sw.getClassName());
                if (cn.contains("Switch") || cn.contains("CheckBox") || cn.contains("Toggle")) return sw;
                sw = sw.getParent();
            }
            return labeled;
        }
        AccessibilityNodeInfo sw = findClass(root, "android.widget.Switch");
        if (sw != null) return sw;
        sw = findClass(root, "androidx.appcompat.widget.SwitchCompat");
        if (sw != null) return sw;
        sw = findClass(root, "android.widget.CheckBox");
        return sw != null ? sw : findClass(root, "android.widget.ToggleButton");
    }

    private boolean isChecked(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int d = 0; d < 5 && cur != null; d++) {
            if (cur.isCheckable() || safe(cur.getClassName()).contains("Switch")) return cur.isChecked();
            cur = cur.getParent();
        }
        return false;
    }

    private boolean matchesText(AccessibilityNodeInfo n, String kw) {
        return cn(safe(n != null ? n.getText() : null), kw)
            || cn(safe(n != null ? n.getContentDescription() : null), kw)
            || cn(getHint(n), kw);
    }

    private boolean matchesAny(AccessibilityNodeInfo n, String... kws) {
        if (n == null) return false;
        for (String kw : kws)
            if (matchesText(n, kw) || cn(safe(n.getViewIdResourceName()), kw)) return true;
        return false;
    }

    private boolean cn(String src, String needle) { return norm(src).contains(norm(needle)); }
    private String norm(String v) {
        return safe(v).toLowerCase()
                .replace("á","a").replace("é","e").replace("í","i")
                .replace("ó","o").replace("ú","u").replace("ñ","n")
                .replaceAll("\\s+", " ").trim();
    }

    private void findFields(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> res) {
        if (n == null) return;
        if (n.isEditable() || safe(n.getClassName()).contains("EditText")) res.add(n);
        for (int i = 0; i < n.getChildCount(); i++) findFields(n.getChild(i), res);
    }

    private void findClickable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> res) {
        if (n == null) return;
        if (n.isClickable()) res.add(n);
        for (int i = 0; i < n.getChildCount(); i++) findClickable(n.getChild(i), res);
    }

    private void scheduleRetry(long delayMs) {
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.postDelayed(retryRunnable, delayMs + 180L);
    }

    private void retryProcess() {
        if (state == State.IDLE || state == State.DECODING) return;
        process(getRootInActiveWindow());
    }

    private String sanitizeAmount(String raw) {
        String s = safe(raw).trim().replace(',', '.');
        return s.isEmpty() ? "5.00" : s;
    }

    private String sanitizeReference(String raw) {
        String s = safe(raw).trim();
        return s.isEmpty() ? "PAGO_AUTO" : s;
    }

    // ─── Status popup ─────────────────────────────────────────────────────────

    private void showStatus(String msg) {
        Log.i(TAG, msg);
        showToast(msg);
        showPopup(msg);
    }

    private void showPopup(String message) {
        mainHandler.post(() -> {
            if (windowManager == null) return;
            if (statusPopup == null) {
                statusPopup = new TextView(this);
                statusPopup.setTextColor(Color.WHITE);
                statusPopup.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                statusPopup.setPadding(36, 20, 36, 20);
                statusPopup.setBackgroundColor(0xE8111111);
                statusPopup.setGravity(Gravity.CENTER);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                params.y = 72;
                windowManager.addView(statusPopup, params);
            }
            statusPopup.setVisibility(View.VISIBLE);
            statusPopup.setText(message);
            mainHandler.removeCallbacks(hidePopupRunnable);
            mainHandler.postDelayed(hidePopupRunnable, 2200L);
        });
    }

    private void hidePopup() {
        mainHandler.post(() -> {
            if (statusPopup != null) {
                try { windowManager.removeView(statusPopup); } catch (Exception ignored) {}
                statusPopup = null;
            }
        });
    }

    // ─── Micro utils ──────────────────────────────────────────────────────────

    private Rect   getBounds(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r; }
    private String getHint(AccessibilityNodeInfo n) {
        if (n == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "";
        return safe(n.getHintText());
    }
    private String safe(CharSequence v) { return v == null ? "" : v.toString(); }
    private void   sleep(long ms)       { try { Thread.sleep(ms); } catch (Exception ignored) {} }
    private void   showToast(String m)  { mainHandler.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
}
