package com.example.orientar.announcements.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.orientar.R;
import com.example.orientar.announcements.models.FormalAnnouncement;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FormalAnnouncementsAdapter extends RecyclerView.Adapter<FormalAnnouncementsAdapter.VH> {

    private final List<FormalAnnouncement> items = new ArrayList<>();

    public void submitList(List<FormalAnnouncement> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_formal_announcement, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        FormalAnnouncement item = items.get(position);

        holder.tvTitle.setText(item.title != null ? item.title : "");
        holder.tvMessage.setText(item.message != null ? item.message : "");

        String by = item.createdBy != null && !item.createdBy.isEmpty() ? item.createdBy : "unknown";
        String dateText = "-";

        if (item.createdAt != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
            dateText = sdf.format(item.createdAt.toDate());
        }

        holder.tvMeta.setText("By " + by + " • " + dateText);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvMessage, tvMeta;

        public VH(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvFormalTitle);
            tvMessage = itemView.findViewById(R.id.tvFormalMessage);
            tvMeta = itemView.findViewById(R.id.tvFormalMeta);
        }
    }
}