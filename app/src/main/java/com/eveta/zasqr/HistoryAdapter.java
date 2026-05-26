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

    private List<HistoryEntry> items;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());

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
        h.tvAmount.setText("Bs " + e.amount);
        h.tvRef.setText(e.concept.isEmpty() ? e.reference : e.concept + "  |  " + e.reference);
        h.tvPayload.setText(e.qrPayload.isEmpty() ? "(sin payload)" : e.qrPayload);
        h.tvTime.setText(FMT.format(new Date(e.timestampMs)));

        if (!e.submitted) {
            h.tvStatus.setText("Error");
            h.tvStatus.setTextColor(0xFFC62828);
        } else if (!e.verified) {
            h.tvStatus.setText("Pendiente");
            h.tvStatus.setTextColor(0xFFE65100);
        } else {
            h.tvStatus.setText("Pagado");
            h.tvStatus.setTextColor(0xFF2E7D32);
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvAmount, tvRef, tvPayload, tvTime, tvStatus;
        VH(View v) {
            super(v);
            tvAmount  = v.findViewById(R.id.tvAmount);
            tvRef     = v.findViewById(R.id.tvRef);
            tvPayload = v.findViewById(R.id.tvPayload);
            tvTime    = v.findViewById(R.id.tvTime);
            tvStatus  = v.findViewById(R.id.tvStatus);
        }
    }
}
