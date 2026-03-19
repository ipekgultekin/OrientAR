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
import com.example.orientar.announcements.models.GroupAnnouncement;
import com.google.firebase.Timestamp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GroupAnnouncementsAdapter
        extends ListAdapter<GroupAnnouncement, GroupAnnouncementsAdapter.GroupAnnouncementViewHolder> {

    public GroupAnnouncementsAdapter() {
        super(DIFF_CALLBACK);
    }

    private static final DiffUtil.ItemCallback<GroupAnnouncement> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<GroupAnnouncement>() {
                @Override
                public boolean areItemsTheSame(@NonNull GroupAnnouncement oldItem, @NonNull GroupAnnouncement newItem) {
                    return safe(oldItem.id).equals(safe(newItem.id));
                }

                @Override
                public boolean areContentsTheSame(@NonNull GroupAnnouncement oldItem, @NonNull GroupAnnouncement newItem) {
                    return safe(oldItem.title).equals(safe(newItem.title))
                            && safe(oldItem.message).equals(safe(newItem.message))
                            && safe(oldItem.groupId).equals(safe(newItem.groupId))
                            && safe(oldItem.leaderId).equals(safe(newItem.leaderId))
                            && safe(oldItem.leaderName).equals(safe(newItem.leaderName))
                            && oldItem.isActive == newItem.isActive;
                }
            };

    @NonNull
    @Override
    public GroupAnnouncementViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group_announcement, parent, false);
        return new GroupAnnouncementViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull GroupAnnouncementViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    static class GroupAnnouncementViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvTitle;
        private final TextView tvMessage;
        private final TextView tvMeta;

        public GroupAnnouncementViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvMeta = itemView.findViewById(R.id.tvMeta);
        }

        void bind(GroupAnnouncement item) {
            tvTitle.setText(safe(item.title));
            tvMessage.setText(safe(item.message));

            String leader = safe(item.leaderName).isEmpty() ? "Leader" : item.leaderName;
            String date = formatTimestamp(item.createdAt);

            tvMeta.setText("By " + leader + " • " + date);
        }

        private String formatTimestamp(Timestamp timestamp) {
            if (timestamp == null) return "-";
            Date date = timestamp.toDate();
            return new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(date);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
