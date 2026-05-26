package com.eveta.zasqr;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    private static final long EXPIRED_MS = 10 * 60 * 1000L;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

    private List<HistoryEntry> items;

    HistoryAdapter(List<HistoryEntry> items) { this.items = items; }

    void update(List<HistoryEntry> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        HistoryEntry e = items.get(pos);

        // Amount (right column)
        h.tvAmount.setText("Bs " + e.amount);

        // Reference — show once; if concept == reference or concept empty, just show one
        String ref = e.reference;
        String concept = e.concept;
        if (concept.isEmpty() || concept.equals(ref)) {
            h.tvRef.setText(ref.isEmpty() ? "(sin ref)" : ref);
        } else {
            h.tvRef.setText(concept + "  ·  " + ref);
        }

        // Timestamps
        String timeLine;
        if (e.requestAtMs > 0 && e.timestampMs > 0) {
            timeLine = "Pet: " + FMT.format(new Date(e.requestAtMs))
                     + "  ·  Env: " + FMT.format(new Date(e.timestampMs));
        } else if (e.timestampMs > 0) {
            timeLine = FMT.format(new Date(e.timestampMs));
        } else {
            timeLine = "";
        }
        h.tvTime.setText(timeLine);

        // Payload preview (1 line) + full payload
        String payload = e.qrPayload.isEmpty() ? "(sin payload)" : e.qrPayload;
        h.tvPayload.setText(payload);
        h.tvPayloadFull.setText(payload);

        // Expand/collapse on ··· button
        h.tvPayloadFull.setVisibility(View.GONE);
        h.btnExpand.setOnClickListener(v -> {
            boolean expanded = h.tvPayloadFull.getVisibility() == View.VISIBLE;
            h.tvPayloadFull.setVisibility(expanded ? View.GONE : View.VISIBLE);
            h.btnExpand.setText(expanded ? "···" : "▲");
        });

        // Status: Vencido > Pagado > Pendiente > Error
        long now = System.currentTimeMillis();
        boolean expired = e.submitted && !e.verified
                && e.timestampMs > 0
                && (now - e.timestampMs) > EXPIRED_MS;

        if (!e.submitted) {
            h.tvStatus.setText("Error");
            h.tvStatus.setTextColor(0xFFC62828);
        } else if (e.verified) {
            h.tvStatus.setText("Pagado");
            h.tvStatus.setTextColor(0xFF2E7D32);
        } else if (expired) {
            h.tvStatus.setText("Vencido");
            h.tvStatus.setTextColor(0xFF757575);
        } else {
            h.tvStatus.setText("Pendiente");
            h.tvStatus.setTextColor(0xFFE65100);
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvAmount, tvRef, tvTime, tvPayload, tvPayloadFull, btnExpand, tvStatus;
        VH(View v) {
            super(v);
            tvAmount      = v.findViewById(R.id.tvAmount);
            tvRef         = v.findViewById(R.id.tvRef);
            tvTime        = v.findViewById(R.id.tvTime);
            tvPayload     = v.findViewById(R.id.tvPayload);
            tvPayloadFull = v.findViewById(R.id.tvPayloadFull);
            btnExpand     = v.findViewById(R.id.btnExpand);
            tvStatus      = v.findViewById(R.id.tvStatus);
        }
    }
}
