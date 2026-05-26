package com.example.zasmvp;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ZasAccessibilityService extends AccessibilityService {
    private static final String TAG = "ZasService";
    private static final String ZAS_PACKAGE = "bec.vdb.direct";
    private static final long ACTION_DELAY_MS = 900L;
    private static ZasAccessibilityService instance;
    private enum State { IDLE, EDITING, FILLING, CONFIGURING, GENERATING, WAITING_FOR_SAVE, SAVING, RESETTING }
    private State state = State.IDLE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String amt = "5.00";
    private String ref = "PAGO_AUTO";
    private boolean validitySelected;
    private boolean singleUseEnabled;
    private boolean amountFieldPrepared;
    private boolean keyboardDismissed;
    private long nextActionAtMs;
    private WindowManager windowManager;
    private TextView statusPopup;
    private final Runnable hidePopupRunnable = this::hidePopup;
    private final Runnable retryRunnable = this::retryProcess;

    public static ZasAccessibilityService getInstance() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showStatus("Servicio ZAS conectado");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hidePopup();
        mainHandler.removeCallbacks(retryRunnable);
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (pkg.equals(ZAS_PACKAGE)) {
            Log.d(TAG, "Evento detectado en ZAS: " + event.getEventType());
            if (state != State.IDLE && shouldProcessEvent(event.getEventType())) {
                process(getRootInActiveWindow());
            }
        }
    }

    public void startAutoFlow(String amt, String ref) {
        this.state = State.EDITING;
        this.amt = sanitizeAmount(amt);
        this.ref = sanitizeReference(ref);
        this.validitySelected = false;
        this.singleUseEnabled = false;
        this.amountFieldPrepared = false;
        this.keyboardDismissed = false;
        this.nextActionAtMs = 0L;
        mainHandler.removeCallbacks(retryRunnable);
        showStatus("Iniciando automatizacion");
        Intent i = getPackageManager().getLaunchIntentForPackage(ZAS_PACKAGE);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(ZAS_PACKAGE, ZAS_PACKAGE + ".MainActivity");
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void process(AccessibilityNodeInfo root) {
        if (root == null) return;
        if (System.currentTimeMillis() < nextActionAtMs) return;

        switch (state) {
            case EDITING:
                if (openEditor(root)) {
                    advance(State.FILLING, ACTION_DELAY_MS, "Entrando al editor");
                }
                break;
            case FILLING:
                if (fillForm(root)) {
                    advance(State.CONFIGURING, ACTION_DELAY_MS, "Monto y motivo listos");
                }
                break;
            case CONFIGURING:
                if (configureForm(root)) {
                    advance(State.GENERATING, ACTION_DELAY_MS, "Configuracion completa");
                }
                break;
            case GENERATING:
                if (clickByLabels(root, "Generar")) {
                    advance(State.WAITING_FOR_SAVE, 1500L, "Generando QR");
                }
                break;
            case WAITING_FOR_SAVE:
                if (saveResult(root)) {
                    advance(State.SAVING, ACTION_DELAY_MS, "Guardando QR");
                }
                break;
            case SAVING:
                if (openEditor(root)) {
                    advance(State.RESETTING, ACTION_DELAY_MS, "Volviendo a limpiar");
                }
                break;
            case RESETTING:
                if (clickByLabels(root, "Limpiar")) {
                    showStatus("Proceso finalizado");
                    state = State.IDLE;
                }
                break;
        }
    }

    private boolean fillForm(AccessibilityNodeInfo root) {
        Log.d(TAG, "DEBUG: FILLING STATE. Total Nodes: " + countNodes(root));
        StringBuilder sb = new StringBuilder();
        dumpNode(root, 0, sb, 0, 160);
        Log.d(TAG, sb.toString());

        AccessibilityNodeInfo amountField = findFieldByKeywords(root,
                "monto", "importe", "amount", "valor");
        AccessibilityNodeInfo referenceField = findFieldByKeywords(root,
                "motivo", "referencia", "detalle", "descripcion", "glosa", "concepto");

        List<AccessibilityNodeInfo> fields = collectEditableFields(root);
        if (amountField == null && fields.size() > 0) amountField = fields.get(0);
        if (referenceField == null && fields.size() > 1) {
            referenceField = fields.get(amountField == fields.get(0) ? 1 : 0);
        }

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
            showStatus("Monto listo para escribir");
            nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
            scheduleRetry(ACTION_DELAY_MS);
            return false;
        }

        boolean amountOk = setText(amountField, amt);
        if (!amountOk) {
            showStatus("Reintentando monto");
            nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
            return false;
        }

        sleep(250);

        boolean refOk = setText(referenceField, ref);
        Log.d(TAG, "Resultado fillForm amountOk=" + amountOk + " refOk=" + refOk);
        if (amountOk && refOk) {
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

    private int countNodes(AccessibilityNodeInfo n) {
        if (n == null) return 0;
        int count = 1;
        for (int i = 0; i < n.getChildCount(); i++) count += countNodes(n.getChild(i));
        return count;
    }

    private boolean openEditor(AccessibilityNodeInfo root) {
        if (clickByLabels(root, "Editar", "Nuevo QR", "Crear QR")) return true;
        return clickBottom(root, 0);
    }

    private boolean saveResult(AccessibilityNodeInfo root) {
        if (clickByLabels(root, "Guardar")) return true;
        return clickBottom(root, 1);
    }

    private boolean configureForm(AccessibilityNodeInfo root) {
        if (!validitySelected) {
            if (clickByLabels(root, "1 dia", "1 día")) {
                validitySelected = true;
                keyboardDismissed = true;
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                showStatus("Validez seleccionada: 1 dia");
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }

            AccessibilityNodeInfo validityControl = findClickableNodeByKeywords(root,
                    "validez", "vigencia", "vencimiento", "duracion", "duración");
            if (validityControl != null && performClick(validityControl)) {
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                showStatus("Abriendo selector de validez");
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }

            Log.d(TAG, "No se encontro selector de validez en esta ventana");
            return false;
        }

        if (!singleUseEnabled) {
            AccessibilityNodeInfo toggle = findSingleUseToggle(root);
            if (toggle == null) {
                Log.d(TAG, "No se encontro switch de un solo uso");
                return false;
            }

            if (!isChecked(toggle)) {
                if (!performClick(toggle)) return false;
                nextActionAtMs = System.currentTimeMillis() + ACTION_DELAY_MS;
                showStatus("Activando un solo uso");
                scheduleRetry(ACTION_DELAY_MS);
                return false;
            }

            singleUseEnabled = true;
        }

        return validitySelected && singleUseEnabled;
    }

    private boolean clickBottom(AccessibilityNodeInfo root, int index) {
        List<AccessibilityNodeInfo> clickables = new ArrayList<>();
        findClickable(root, clickables);
        List<AccessibilityNodeInfo> bottomNodes = new ArrayList<>();
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int minTop = (int) (screenHeight * 0.72f);
        for (AccessibilityNodeInfo node : clickables) {
            Rect r = getBounds(node);
            if (r.top >= minTop) bottomNodes.add(node);
        }
        Collections.sort(bottomNodes, (a, b) -> {
            Rect ar = getBounds(a);
            Rect br = getBounds(b);
            int byTop = Integer.compare(ar.top, br.top);
            return byTop != 0 ? byTop : Integer.compare(ar.left, br.left);
        });
        return bottomNodes.size() > index && performClick(bottomNodes.get(index));
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

    private AccessibilityNodeInfo findLabel(AccessibilityNodeInfo n, String l) {
        if (n == null) return null;
        if (matchesText(n, l)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findLabel(n.getChild(i), l);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findClass(AccessibilityNodeInfo n, String c) {
        if (n == null) return null;
        if (safe(n.getClassName()).equals(c)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findClass(n.getChild(i), c);
            if (f != null) return f;
        }
        return null;
    }

    private List<AccessibilityNodeInfo> collectEditableFields(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        findFields(root, fields);
        Collections.sort(fields, (a, b) -> Integer.compare(getBounds(a).top, getBounds(b).top));
        return fields;
    }

    private AccessibilityNodeInfo findFieldByKeywords(AccessibilityNodeInfo root, String... keywords) {
        for (AccessibilityNodeInfo field : collectEditableFields(root)) {
            if (matchesAny(field, keywords)) return field;
            AccessibilityNodeInfo parent = field.getParent();
            if (matchesAny(parent, keywords)) return field;
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableNodeByKeywords(AccessibilityNodeInfo root, String... keywords) {
        if (root == null) return null;
        if (matchesAny(root, keywords) && (root.isClickable() || root.isFocusable())) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findClickableNodeByKeywords(root.getChild(i), keywords);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findSingleUseToggle(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo labeled = findClickableNodeByKeywords(root,
                "un solo uso", "solo uso", "pago unico", "pago único", "unico", "único");
        if (labeled != null) {
            AccessibilityNodeInfo switchNode = labeled;
            for (int i = 0; i < 4 && switchNode != null; i++) {
                if (safe(switchNode.getClassName()).contains("Switch")
                        || safe(switchNode.getClassName()).contains("CheckBox")
                        || safe(switchNode.getClassName()).contains("Toggle")) {
                    return switchNode;
                }
                switchNode = switchNode.getParent();
            }
            return labeled;
        }

        AccessibilityNodeInfo switchNode = findClass(root, "android.widget.Switch");
        if (switchNode != null) return switchNode;
        switchNode = findClass(root, "androidx.appcompat.widget.SwitchCompat");
        if (switchNode != null) return switchNode;
        switchNode = findClass(root, "android.widget.CheckBox");
        if (switchNode != null) return switchNode;
        return findClass(root, "android.widget.ToggleButton");
    }

    private boolean clickByLabels(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            AccessibilityNodeInfo node = findLabel(root, label);
            if (node != null && performClick(node)) return true;
        }
        return false;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
        CharSequence currentText = node.getText();
        return ok || value.equals(safe(currentText));
    }

    private boolean primeAmountField(AccessibilityNodeInfo node) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean first = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        sleep(260);
        boolean second = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        sleep(260);
        return first || second;
    }

    private boolean matchesText(AccessibilityNodeInfo node, String keyword) {
        return containsNormalized(safe(node != null ? node.getText() : null), keyword)
                || containsNormalized(safe(node != null ? node.getContentDescription() : null), keyword)
                || containsNormalized(getHint(node), keyword);
    }

    private boolean matchesAny(AccessibilityNodeInfo node, String... keywords) {
        if (node == null) return false;
        for (String keyword : keywords) {
            if (matchesText(node, keyword)
                    || containsNormalized(safe(node.getViewIdResourceName()), keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNormalized(String source, String needle) {
        return normalize(source).contains(normalize(needle));
    }

    private String normalize(String value) {
        String normalized = safe(value).toLowerCase()
                .replace("á", "a")
                .replace("é", "e")
                .replace("í", "i")
                .replace("ó", "o")
                .replace("ú", "u")
                .replace("ñ", "n");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private boolean isChecked(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current.isCheckable() || safe(current.getClassName()).contains("Switch")) {
                return current.isChecked();
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean shouldProcessEvent(int eventType) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
    }

    private void advance(State newState, long delayMs, String status) {
        state = newState;
        nextActionAtMs = System.currentTimeMillis() + delayMs;
        scheduleRetry(delayMs);
        showStatus(status);
    }

    private void scheduleRetry(long delayMs) {
        mainHandler.removeCallbacks(retryRunnable);
        mainHandler.postDelayed(retryRunnable, delayMs + 180L);
    }

    private void retryProcess() {
        if (state == State.IDLE) return;
        process(getRootInActiveWindow());
    }

    private String sanitizeAmount(String raw) {
        String normalized = safe(raw).trim().replace(',', '.');
        return normalized.isEmpty() ? "5.00" : normalized;
    }

    private String sanitizeReference(String raw) {
        String normalized = safe(raw).trim();
        return normalized.isEmpty() ? "PAGO_AUTO" : normalized;
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth, StringBuilder sb, int visited, int limit) {
        if (node == null || visited >= limit) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        Rect bounds = getBounds(node);
        sb.append("- class=").append(safe(node.getClassName()))
                .append(" text=").append(safe(node.getText()))
                .append(" hint=").append(getHint(node))
                .append(" desc=").append(safe(node.getContentDescription()))
                .append(" id=").append(safe(node.getViewIdResourceName()))
                .append(" editable=").append(node.isEditable())
                .append(" clickable=").append(node.isClickable())
                .append(" bounds=").append(bounds)
                .append('\n');
        for (int i = 0; i < node.getChildCount() && sb.length() < 16000; i++) {
            dumpNode(node.getChild(i), depth + 1, sb, visited + 1, limit);
        }
    }

    private void showStatus(String message) {
        Log.i(TAG, message);
        showToast(message);
        showPopup(message);
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
                        android.graphics.PixelFormat.TRANSLUCENT
                );
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
                try {
                    statusPopup.setVisibility(View.GONE);
                    windowManager.removeView(statusPopup);
                } catch (Exception ignored) {
                }
                statusPopup = null;
            }
        });
    }

    private Rect getBounds(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r; }
    private String getHint(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "";
        return safe(node.getHintText());
    }
    private String safe(CharSequence v) { return v == null ? "" : v.toString(); }
    private void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception ignored) {} }
    private void showToast(String m) { mainHandler.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
    @Override public void onInterrupt() {}
}
