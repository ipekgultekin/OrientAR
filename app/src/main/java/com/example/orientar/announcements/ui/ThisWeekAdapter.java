package com.example.orientar.announcements.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.orientar.R;
import com.example.orientar.announcements.models.ThisWeekEvent;

public class ThisWeekAdapter extends ListAdapter<ThisWeekEvent, ThisWeekAdapter.VH> {

    public ThisWeekAdapter() { super(DIFF); }

    static DiffUtil.ItemCallback<ThisWeekEvent> DIFF = new DiffUtil.ItemCallback<ThisWeekEvent>() {
        @Override
        public boolean areItemsTheSame(@NonNull ThisWeekEvent a, @NonNull ThisWeekEvent b) {
            return (a.title + a.date_text).equals(b.title + b.date_text);
        }

        @Override
        public boolean areContentsTheSame(@NonNull ThisWeekEvent a, @NonNull ThisWeekEvent b) {
            return safe(a.title).equals(safe(b.title))
                    && safe(a.date_text).equals(safe(b.date_text))
                    && safe(a.time_text).equals(safe(b.time_text))
                    && safe(a.location).equals(safe(b.location))
                    && safe(a.description).equals(safe(b.description));
        }

        private String safe(String s) { return s == null ? "" : s; }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_this_week_event, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        ThisWeekEvent ev = getItem(pos);

        h.tvTitle.setText(ev.title == null || ev.title.isEmpty() ? "Event" : ev.title);

        String meta = "";
        if (ev.date_text != null && !ev.date_text.isEmpty()) meta += ev.date_text;
        if (ev.time_text != null && !ev.time_text.isEmpty()) meta += "  " + ev.time_text;
        if (ev.location != null && !ev.location.isEmpty()) meta += " • " + ev.location;

        h.tvMeta.setText(meta.trim());
        h.tvDesc.setText(ev.description == null ? "" : ev.description);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvMeta, tvDesc;

        VH(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvEventTitle);
            tvMeta  = itemView.findViewById(R.id.tvEventMeta);
            tvDesc  = itemView.findViewById(R.id.tvEventDesc);
        }
    }
}