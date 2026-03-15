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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.orientar.R;
import com.example.orientar.announcements.models.ThisWeekResponse;
import com.example.orientar.announcements.ui.FormalAnnouncementsAdapter;
import com.example.orientar.announcements.ui.ThisWeekAdapter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class AnnouncementsFragment extends Fragment {

    private AnnouncementsViewModel vm;
    private ThisWeekAdapter thisWeekAdapter;
    private FormalAnnouncementsAdapter formalAdapter;

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

        RecyclerView rvFormal = v.findViewById(R.id.rvFormalAnnouncements);
        rvFormal.setLayoutManager(new LinearLayoutManager(requireContext()));
        formalAdapter = new FormalAnnouncementsAdapter();
        rvFormal.setAdapter(formalAdapter);

        RecyclerView rvThisWeek = v.findViewById(R.id.rvThisWeek);
        rvThisWeek.setLayoutManager(new LinearLayoutManager(requireContext()));
        thisWeekAdapter = new ThisWeekAdapter();
        rvThisWeek.setAdapter(thisWeekAdapter);

        vm = new ViewModelProvider(this).get(AnnouncementsViewModel.class);

        btnRetry.setOnClickListener(x -> vm.refresh());

        vm.getState().observe(getViewLifecycleOwner(), s -> {
            progress.setVisibility(s.loading ? View.VISIBLE : View.GONE);
            errorBox.setVisibility(s.error != null ? View.VISIBLE : View.GONE);

            if (s.error != null) {
                tvError.setText(s.error);
            }

            if (s.formalAnnouncements != null) {
                formalAdapter.submitList(s.formalAnnouncements);
            }

            if (s.thisWeekData != null) {
                bindThisWeekData(s.thisWeekData);
            }
        });

        vm.refresh();
    }

    private void bindThisWeekData(ThisWeekResponse data) {
        tvHeroTitle.setText("Announcements");
        tvWeekRange.setText(data.week_range_text == null ? "" : data.week_range_text);
        tvUpdatedAt.setText(formatUpdatedAt(data.updated_at));
        tvSource.setText(data.source_url == null ? "" : data.source_url);
        thisWeekAdapter.submitList(data.events);
    }

    private String formatUpdatedAt(String iso) {
        if (iso == null || iso.isEmpty()) return "Last updated: -";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                OffsetDateTime dt = OffsetDateTime.parse(iso);
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.getDefault());
                return "Last updated: " + dt.format(fmt);
            }
        } catch (Exception e) {
            return "Last updated: " + iso;
        }
        return "Last updated: " + iso;
    }
}