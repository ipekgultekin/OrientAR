package com.example.orientar.announcements;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.orientar.R;
import com.example.orientar.announcements.models.ThisWeekResponse;
import com.example.orientar.announcements.ui.ThisWeekAdapter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class AnnouncementsFragment extends Fragment {

    private AnnouncementsViewModel vm;
    private ThisWeekAdapter adapter;

    private ProgressBar progress;
    private View errorBox;
    private TextView tvError, btnRetry;

    private TextView tvHeroTitle, tvWeekRange, tvUpdatedAt, tvSource;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_announcements, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        progress = v.findViewById(R.id.progress);
        errorBox = v.findViewById(R.id.errorBox);
        tvError = v.findViewById(R.id.tvError);
        btnRetry = v.findViewById(R.id.btnRetry);

        tvHeroTitle = v.findViewById(R.id.tvHeroTitle);
        tvWeekRange = v.findViewById(R.id.tvWeekRange);
        tvUpdatedAt = v.findViewById(R.id.tvUpdatedAt);
        tvSource = v.findViewById(R.id.tvSource);

        RecyclerView rv = v.findViewById(R.id.rvThisWeek);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ThisWeekAdapter();
        rv.setAdapter(adapter);

        vm = new ViewModelProvider(this).get(AnnouncementsViewModel.class);

        btnRetry.setOnClickListener(x -> vm.refresh());

        vm.getState().observe(getViewLifecycleOwner(), s -> {
            progress.setVisibility(s.loading ? View.VISIBLE : View.GONE);
            errorBox.setVisibility(s.error != null ? View.VISIBLE : View.GONE);

            if (s.error != null) tvError.setText(s.error);

            if (s.data != null) bindData(s.data);
        });

        vm.refresh();
    }

    private void bindData(ThisWeekResponse data) {
        // Hero title
        tvHeroTitle.setText("Announcements");

        // week_range_text = "This Week on Campus"
        tvWeekRange.setText(data.week_range_text == null ? "" : data.week_range_text);

        // updated_at string -> "Last updated: 24 Feb 2026, 15:59"
        tvUpdatedAt.setText(formatUpdatedAt(data.updated_at));

        // source_url
        tvSource.setText(data.source_url == null ? "" : data.source_url);

        adapter.submitList(data.events);
    }

    private String formatUpdatedAt(String iso) {
        if (iso == null || iso.isEmpty()) return "Last updated: -";
        try {
            OffsetDateTime dt = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dt = OffsetDateTime.parse(iso);
            }
            DateTimeFormatter fmt = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                fmt = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.getDefault());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return "Last updated: " + dt.format(fmt);
            }
        } catch (Exception e) {
            return "Last updated: " + iso;
        }
        return iso;
    }
}