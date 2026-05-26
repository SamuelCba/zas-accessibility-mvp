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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ZasAccessibilityService extends AccessibilityService {

    private static final String TAG         = "ZasQRG";
    private static final String ZAS_PACKAGE = "bec.vdb.direct";
    private static final long   ACTION_DELAY_MS  = 500L;
    private static final long   POLL_INTERVAL_MS = 3000L;
    private static final long   VERIFY_COOLDOWN_MS = 20000L;
    static  final String        ACTION_HISTORY_UPDATED = "com.eveta.zasqr.HISTORY_UPDATED";

    private static ZasAccessibilityService instance;

    private enum State {
        IDLE,
        EDITING, FILLING, CONFIGURING, GENERATING, WAITING_FOR_SAVE, DECODING,
        VERIFY_NAV, VERIFY_SELECT, VERIFY_READ
    }

    private State   state = State.IDLE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // QR generation fields
    private String  amt = "5.00";
    private String  ref = "PAGO_AUTO";
    private boolean validitySelected;
    private boolean singleUseEnabled;
    private boolean amountFieldPrepared;
    private boolean keyboardDismissed;
    private long    nextActionAtMs;
    private long    preGenMaxQrId = -1L;
    private BackendClient.TopupData currentTopup;

    // Verification
    private long lastVerifyAtMs   = 0L;
    private int  verifyScrollsDone = 0;
    private int  verifyLoadTries   = 0;
    private final Map<String, Integer> verifyEntries = new LinkedHashMap<>();

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

    // ─── Polling ─────────────────────────────────────────────────────────────

    private void schedulePoll(long delayMs) {
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.postDelayed(pollRunnable, delayMs);
    }

    private void doPoll() {
        if (state != State.IDLE) { schedulePoll(POLL_INTERVAL_MS); return; }
        if (BackendClient.token(this).isEmpty()) { schedulePoll(POLL_INTERVAL_MS); return; }

        new Thread(() -> {
            try {
                BackendClient.TopupData topup = BackendClient.getNextTopup(this);
                mainHandler.post(() -> {
                    if (state != State.IDLE) { schedulePoll(POLL_INTERVAL_MS); return; }

                    if (topup != null) {
                        if (HistoryStorage.alreadyProcessed(this, topup.id)) {
                            Log.w(TAG, "Topup " + topup.id + " ya procesado");
                            schedulePoll(POLL_INTERVAL_MS);
                            return;
                        }
                        currentTopup = topup;
                        startAutoFlow(topup.amount, topup.reference);
                        return;
                    }

                    // No topups — check if we should verify
                    long now = System.currentTimeMillis();
                    if (HistoryStorage.hasUnverified(this)
                            && (now - lastVerifyAtMs) > VERIFY_COOLDOWN_MS) {
                        lastVerifyAtMs = now;
                        startVerification();
                    } else {
                        schedulePoll(POLL_INTERVAL_MS);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "poll: " + e.getMessage());
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
        launchZas(false);
        scheduleRetry(1500L); // safety: fire even if no window event arrives
    }

    private void startVerification() {
        verifyScrollsDone = 0;
        verifyLoadTries   = 0;
        verifyEntries.clear();
        state = State.VERIFY_NAV;
        showStatus("Verificando pagos...");
        launchZas(true);
        nextActionAtMs = System.currentTimeMillis() + 1200L;
        scheduleRetry(1200L);
    }

    private void launchZas(boolean singleTop) {
        Intent i = getPackageManager().getLaunchIntentForPackage(ZAS_PACKAGE);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(ZAS_PACKAGE, ZAS_PACKAGE + ".MainActivity");
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (singleTop) i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ─── Accessibility events ────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!pkg.equals(ZAS_PACKAGE)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // Always handle payment popup regardless of state
        if (handlePaymentPopup(root)) return;

        if (state != State.IDLE && shouldProcessEvent(event.getEventType())) {
            process(root);
        }
    }

    private boolean shouldProcessEvent(int t) {
        return t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            || t == AccessibilityEvent.TYPE_VIEW_CLICKED
            || t == AccessibilityEvent.TYPE_VIEW_SCROLLED
            || t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
    }

    // ─── Payment popup handler ───────────────────────────────────────────────

    private boolean handlePaymentPopup(AccessibilityNodeInfo root) {
        if (findLabel(root, "Pago QR recibido") == null) return false;
        showStatus("Pago recibido — continuando...");
        if (clickByLabels(root, "Cerrar")) {
            // Resume current flow after a small pause
            if (state != State.IDLE && state != State.DECODING) {
                scheduleRetry(ACTION_DELAY_MS * 2);
            }
        }
        return true;
    }

    // ─── State machine ───────────────────────────────────────────────────────

    private void process(AccessibilityNodeInfo root) {
        if (root == null) return;
        if (System.currentTimeMillis() < nextActionAtMs) return;

        switch (state) {
            // — QR generation —
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
                preGenMaxQrId = getMaxQrId();
                if (clickByLabels(root, "Generar")) advance(State.WAITING_FOR_SAVE, 1200L, "Generando QR...");
                break;
            case WAITING_FOR_SAVE:
                if (saveResult(root)) advance(State.DECODING, ACTION_DELAY_MS, "Imagen guardada");
                break;
            case DECODING:
                break; // background thread

            // — Verification —
            case VERIFY_NAV:
                if (clickByLabels(root, "Reportes")) {
                    advance(State.VERIFY_SELECT, 700L, "Abriendo reportes");
                }
                break;
            case VERIFY_SELECT:
                if (clickByLabels(root, "por Cobro QR", "Cobro QR")) {
                    advance(State.VERIFY_READ, 2000L, "Cargando reporte...");
                }
                break;
            case VERIFY_READ:
                doVerifyStep(root);
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

    // ─── Verification read (event-driven) ───────────────────────────────────

    private void doVerifyStep(AccessibilityNodeInfo root) {
        // Wait for report screen to load (up to 6s)
        if (!isReportScreenVisible(root)) {
            verifyLoadTries++;
            if (verifyLoadTries > 6) {
                showStatus("Reporte no disponible");
                finalizeVerification(root);
            } else {
                showStatus("Esperando reporte... (" + verifyLoadTries + ")");
                nextActionAtMs = System.currentTimeMillis() + 1000L;
                scheduleRetry(1000L);
            }
            return;
        }

        verifyLoadTries = 0;

        // Collect entries visible right now using line-based parsing
        int before = verifyEntries.size();
        collectReportEntries(root, verifyEntries);
        int after = verifyEntries.size();
        showStatus("Reporte: " + after + " entradas (scroll " + verifyScrollsDone + ")");
        Log.d(TAG, "Scroll " + verifyScrollsDone + " entradas=" + verifyEntries);

        // Try to scroll further
        if (verifyScrollsDone < 30) {
            AccessibilityNodeInfo scrollable = findScrollable(root);
            if (scrollable != null) {
                boolean scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (scrolled) {
                    verifyScrollsDone++;
                    nextActionAtMs = System.currentTimeMillis() + 800L;
                    scheduleRetry(800L);
                    return;
                }
            }
        }

        // No more scroll or reached limit — done
        finalizeVerification(root);
    }

    private void finalizeVerification(AccessibilityNodeInfo root) {
        boolean anyChanged = false;
        for (Map.Entry<String, Integer> e : verifyEntries.entrySet()) {
            if (e.getValue() > 0) {
                boolean changed = HistoryStorage.markVerifiedByReference(this, e.getKey());
                if (changed) anyChanged = true;
                Log.i(TAG, "match: " + e.getKey() + " pagos=" + e.getValue());
            }
        }
        if (anyChanged) sendBroadcast(new Intent(ACTION_HISTORY_UPDATED));
        int total = verifyEntries.size();
        verifyEntries.clear();
        verifyScrollsDone = 0;
        verifyLoadTries   = 0;

        // Use the in-app back arrow (←) instead of system back
        if (root == null || !clickByLabels(root,
                "Navegar hacia arriba", "Navigate up", "Atrás", "Atras", "Volver")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }

        showStatus(anyChanged ? "Pagos verificados ✓" : "Sin coincidencias (" + total + " entradas)");
        state = State.IDLE;
        schedulePoll(POLL_INTERVAL_MS);
    }

    /** True if the Cobros QR report screen title is visible (entries may still be loading). */
    private boolean isReportScreenVisible(AccessibilityNodeInfo root) {
        return findLabel(root, "Cobros QR") != null
            || findLabel(root, "Reporte por") != null;
    }

    /**
     * Parses visible report cards → map motivo→pagosRecibidos.
     * Each card has lines: "Bs5.00", "Motivo EVTAXXXXXXXX", "Pagos recibidos: N", ...
     * Handles both separate nodes AND a single multi-line node (split by \n).
     */
    private void collectReportEntries(AccessibilityNodeInfo root, Map<String, Integer> out) {
        // collectLines splits multi-line nodes so "Bs5.00\nMotivo EVT..." is handled correctly
        List<String> lines = new ArrayList<>();
        collectLines(root, lines);

        String  pendingMotivo = null;
        boolean expectValue   = false;

        for (String raw : lines) {
            String t  = raw.trim();
            String tl = t.toLowerCase();
            if (t.isEmpty()) continue;

            if (tl.equals("motivo")) {
                // two-node layout: next line is the reference code
                expectValue   = true;
                pendingMotivo = null;
                continue;
            }
            if (expectValue) {
                expectValue = false;
                if (!tl.startsWith("bs") && !tl.startsWith("pagos")
                        && !tl.startsWith("total") && !tl.startsWith("generado")
                        && !tl.startsWith("reporte")) {
                    pendingMotivo = t;
                    continue;
                }
            }
            if (tl.startsWith("motivo ") && t.length() > 7) {
                // single-node layout: "Motivo EVTAXXXXXXXX"
                pendingMotivo = t.substring(7).trim();
                continue;
            }
            if (pendingMotivo != null && tl.startsWith("pagos recibidos")) {
                String numStr = tl.replaceAll("[^0-9]", "");
                try {
                    int count = numStr.isEmpty() ? 0 : Integer.parseInt(numStr);
                    out.putIfAbsent(pendingMotivo, count);
                } catch (NumberFormatException ignored) {}
                pendingMotivo = null;
            }
        }
    }

    /** Like collectTexts but splits each node's text by newlines — handles multi-line nodes. */
    private void collectLines(AccessibilityNodeInfo n, List<String> out) {
        if (n == null) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            for (String line : t.toString().split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collectLines(n.getChild(i), out);
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findScrollable(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    private void collectTexts(AccessibilityNodeInfo n, List<String> out) {
        if (n == null) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) out.add(t.toString());
        for (int i = 0; i < n.getChildCount(); i++) collectTexts(n.getChild(i), out);
    }

    // ─── Decode & submit ─────────────────────────────────────────────────────

    private void startDecodeAndSubmit() {
        final long snapshotId = preGenMaxQrId;
        new Thread(() -> {
            sleep(2500);

            String payload = null;
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                payload = findAndDecodeQrAfter(snapshotId);
                if (payload != null) break;
                sleep(1500);
            }

            final String qrPayload  = payload != null ? payload : "";
            final boolean hasPayload = !qrPayload.isEmpty();
            Log.i(TAG, hasPayload ? "QR: " + qrPayload : "Sin QR decodificado");

            boolean submitted = false;
            if (hasPayload && currentTopup != null) {
                try {
                    submitted = BackendClient.submitQrPayload(this, currentTopup.id, qrPayload);
                } catch (Exception e) { Log.e(TAG, "Submit: " + e.getMessage()); }
            }

            if (currentTopup != null) {
                HistoryEntry entry  = new HistoryEntry();
                entry.topupId      = currentTopup.id;
                entry.amount       = currentTopup.amount;
                entry.reference    = currentTopup.reference;
                entry.concept      = currentTopup.concept;
                entry.qrPayload    = qrPayload;
                entry.timestampMs  = System.currentTimeMillis();
                entry.submitted    = submitted;
                entry.verified     = false;
                HistoryStorage.add(this, entry);
                sendBroadcast(new Intent(ACTION_HISTORY_UPDATED));
            }

            final boolean ok = submitted;
            mainHandler.post(() -> {
                showStatus(ok ? "Enviado OK" : (hasPayload ? "Decode OK, submit fallido" : "Sin QR"));
                currentTopup = null;
                state = State.IDLE;
                schedulePoll(POLL_INTERVAL_MS);
            });
        }).start();
    }

    // ─── MediaStore + ZXing ──────────────────────────────────────────────────

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

    private String findAndDecodeQrAfter(long snapshotId) {
        String[] proj = {MediaStore.Images.Media._ID};
        String   sel  = MediaStore.Images.Media.DISPLAY_NAME + " LIKE 'QR%'"
                      + " AND " + MediaStore.Images.Media._ID + " > " + snapshotId;
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                MediaStore.Images.Media._ID + " DESC")) {
            if (c != null && c.moveToFirst()) {
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
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
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            bmp.recycle();
            Result r = new QRCodeReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, px))));
            return r.getText();
        } catch (Exception e) { Log.w(TAG, "ZXing: " + e.getMessage()); return null; }
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

        if (amountField == null || referenceField == null) { showStatus("Campos no detectados"); return false; }

        if (!amountFieldPrepared) {
            amountFieldPrepared = primeAmountField(amountField);
            if (!amountFieldPrepared) {
                showStatus("Preparando campo monto");
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }
            nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
            scheduleRetry(ACTION_DELAY_MS);
            return false;
        }

        if (!setText(amountField, amt)) {
            nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
            return false;
        }
        sleep(150);
        boolean refOk = setText(referenceField, ref);

        if (refOk) {
            amountFieldPrepared = false;
            if (!keyboardDismissed) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                keyboardDismissed = true;
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
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }
            AccessibilityNodeInfo vc = findClickableNodeByKeywords(root, "validez", "vigencia", "vencimiento");
            if (vc != null) {
                performClick(vc);
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }
            // Neither button nor dropdown found — already configured from previous session
            validitySelected = true;
        }
        if (!singleUseEnabled) {
            AccessibilityNodeInfo toggle = findSingleUseToggle(root);
            if (toggle == null || isChecked(toggle)) {
                // Not found or already enabled — already configured
                singleUseEnabled = true;
                return true;
            }
            if (!performClick(toggle)) return false;
            nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
            scheduleRetry(ACTION_DELAY_MS);
            return false;
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
        amt = sanitizeAmount(newAmt);
        ref = sanitizeReference(newRef);
        validitySelected  = false;
        singleUseEnabled  = false;
        amountFieldPrepared = false;
        keyboardDismissed = false;
        nextActionAtMs    = 0L;
        preGenMaxQrId     = -1L;
        mainHandler.removeCallbacks(retryRunnable);
    }

    private boolean primeAmountField(AccessibilityNodeInfo node) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean f = node.performAction(AccessibilityNodeInfo.ACTION_CLICK); sleep(150);
        boolean s = node.performAction(AccessibilityNodeInfo.ACTION_CLICK); sleep(150);
        return f || s;
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        sleep(100);
        Bundle clear = new Bundle();
        clear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear);
        sleep(80);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        sleep(100);
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
            if (matchesAny(f.getParent(), kw)) return f;
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
        AccessibilityNodeInfo l = findClickableNodeByKeywords(root,
                "un solo uso", "solo uso", "pago unico", "pago único", "unico", "único");
        if (l != null) {
            AccessibilityNodeInfo sw = l;
            for (int i = 0; i < 4 && sw != null; i++) {
                String cn = safe(sw.getClassName());
                if (cn.contains("Switch") || cn.contains("CheckBox") || cn.contains("Toggle")) return sw;
                sw = sw.getParent();
            }
            return l;
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
        for (String kw : kws) if (matchesText(n, kw) || cn(safe(n.getViewIdResourceName()), kw)) return true;
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
        mainHandler.postDelayed(retryRunnable, delayMs + 150L);
    }

    private void retryProcess() {
        if (state == State.IDLE || state == State.DECODING) return;
        process(getRootInActiveWindow());
    }

    private String sanitizeAmount(String r) { String s = safe(r).trim().replace(',','.'); return s.isEmpty() ? "5.00" : s; }
    private String sanitizeReference(String r) { String s = safe(r).trim(); return s.isEmpty() ? "PAGO_AUTO" : s; }

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
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                p.y = 72;
                windowManager.addView(statusPopup, p);
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

    private Rect   getBounds(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r; }
    private String getHint(AccessibilityNodeInfo n) {
        if (n == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "";
        return safe(n.getHintText());
    }
    private String safe(CharSequence v) { return v == null ? "" : v.toString(); }
    private void   sleep(long ms)       { try { Thread.sleep(ms); } catch (Exception ignored) {} }
    private void   showToast(String m)  { mainHandler.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
}
