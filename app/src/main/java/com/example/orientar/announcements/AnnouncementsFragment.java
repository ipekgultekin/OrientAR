package com.example.orientar.announcements;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.orientar.R;
import com.example.orientar.announcements.models.ThisWeekResponse;
import com.example.orientar.announcements.ui.FormalAnnouncementsAdapter;
import com.example.orientar.announcements.ui.GroupAnnouncementsAdapter;
import com.example.orientar.announcements.ui.ThisWeekAdapter;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class AnnouncementsFragment extends Fragment {

    private AnnouncementsViewModel vm;

    private ThisWeekAdapter thisWeekAdapter;
    private FormalAnnouncementsAdapter formalAdapter;
    private GroupAnnouncementsAdapter groupAdapter;

    private ProgressBar progress;
    private View errorBox;
    private TextView tvError, btnRetry;

    private TextView tvHeroTitle, tvWeekRange, tvUpdatedAt, tvSource;

    private RecyclerView rvThisWeek;
    private RecyclerView rvFormal;
    private RecyclerView rvGroup;
    private TabLayout tabLayoutAnnouncements;
    private Button btnAddGroupAnnouncement;

    private String userRole = "student";
    private String leaderDocId = "";
    private String leaderGroupId = "";
    private String studentGroupId = "";
    private FirebaseFirestore db;

    private int currentTabPosition = 0;

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

        db = FirebaseFirestore.getInstance();

        Bundle args = getArguments();
        if (args != null) {
            userRole = args.getString("USER_ROLE", "student");
            leaderDocId = args.getString("LEADER_DOC_ID", "");
        }

        progress = v.findViewById(R.id.progress);
        errorBox = v.findViewById(R.id.errorBox);
        tvError = v.findViewById(R.id.tvError);
        btnRetry = v.findViewById(R.id.btnRetry);

        tvHeroTitle = v.findViewById(R.id.tvHeroTitle);
        tvWeekRange = v.findViewById(R.id.tvWeekRange);
        tvUpdatedAt = v.findViewById(R.id.tvUpdatedAt);
        tvSource = v.findViewById(R.id.tvSource);

        tabLayoutAnnouncements = v.findViewById(R.id.tabLayoutAnnouncements);
        btnAddGroupAnnouncement = v.findViewById(R.id.btnAddGroupAnnouncement);

        TextView btnBack = v.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(view -> requireActivity().finish());
        }

        rvThisWeek = v.findViewById(R.id.rvThisWeek);
        rvFormal = v.findViewById(R.id.rvFormalAnnouncements);
        rvGroup = v.findViewById(R.id.rvGroupAnnouncements);

        rvThisWeek.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvFormal.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvGroup.setLayoutManager(new LinearLayoutManager(requireContext()));

        thisWeekAdapter = new ThisWeekAdapter();
        formalAdapter = new FormalAnnouncementsAdapter();
        groupAdapter = new GroupAnnouncementsAdapter();

        rvThisWeek.setAdapter(thisWeekAdapter);
        rvFormal.setAdapter(formalAdapter);
        rvGroup.setAdapter(groupAdapter);

        setupTabs();
        updateAddButtonVisibility();

        vm = new ViewModelProvider(this).get(AnnouncementsViewModel.class);

        btnRetry.setOnClickListener(x -> {
            if ("leader".equals(userRole) && !leaderDocId.isEmpty()) {
                loadLeaderGroupAndRefresh();
            } else {
                loadStudentGroupAndRefresh();
            }
        });

        if (btnAddGroupAnnouncement != null) {
            btnAddGroupAnnouncement.setOnClickListener(view -> openAddAnnouncementDialog());
        }

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

            if (s.groupAnnouncements != null) {
                groupAdapter.submitList(s.groupAnnouncements);
            }
        });

        if ("leader".equals(userRole) && !leaderDocId.isEmpty()) {
            loadLeaderGroupAndRefresh();
        } else {
            loadStudentGroupAndRefresh();
        }
    }

    private void loadLeaderGroupAndRefresh() {
        db.collection("orientation_groups")
                .whereEqualTo("leaderId", leaderDocId)
                .limit(1)
                .get()
                .addOnSuccessListener(query -> {
                    if (!query.isEmpty()) {
                        leaderGroupId = query.getDocuments().get(0).getId();
                    } else {
                        leaderGroupId = "";
                    }

                    updateAddButtonVisibility();
                    vm.refresh(leaderGroupId);
                })
                .addOnFailureListener(e -> {
                    leaderGroupId = "";
                    updateAddButtonVisibility();
                    vm.refresh(leaderGroupId);
                });
    }

    private void loadStudentGroupAndRefresh() {
        FirebaseAuth auth = FirebaseAuth.getInstance();

        if (auth.getCurrentUser() == null) {
            studentGroupId = "";
            vm.refresh(studentGroupId);
            return;
        }

        String currentEmail = auth.getCurrentUser().getEmail();
        if (currentEmail == null || currentEmail.trim().isEmpty()) {
            studentGroupId = "";
            vm.refresh(studentGroupId);
            return;
        }

        db.collection("users")
                .whereEqualTo("email", currentEmail.trim().toLowerCase())
                .whereEqualTo("role", "student")
                .limit(1)
                .get()
                .addOnSuccessListener(query -> {
                    if (!query.isEmpty()) {
                        String foundGroupId = query.getDocuments().get(0).getString("groupId");
                        studentGroupId = foundGroupId != null ? foundGroupId : "";
                    } else {
                        studentGroupId = "";
                    }

                    vm.refresh(studentGroupId);
                })
                .addOnFailureListener(e -> {
                    studentGroupId = "";
                    vm.refresh(studentGroupId);
                });
    }

    private void setupTabs() {
        tabLayoutAnnouncements.removeAllTabs();

        tabLayoutAnnouncements.addTab(
                tabLayoutAnnouncements.newTab().setText("This Week")
        );
        tabLayoutAnnouncements.addTab(
                tabLayoutAnnouncements.newTab().setText("Formal")
        );
        tabLayoutAnnouncements.addTab(
                tabLayoutAnnouncements.newTab().setText("Group")
        );

        showTab(0);

        tabLayoutAnnouncements.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    private void showTab(int position) {
        currentTabPosition = position;

        rvThisWeek.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        rvFormal.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        rvGroup.setVisibility(position == 2 ? View.VISIBLE : View.GONE);

        updateAddButtonVisibility();
    }

    private void updateAddButtonVisibility() {
        if (btnAddGroupAnnouncement == null) return;

        boolean shouldShow =
                "leader".equals(userRole)
                        && currentTabPosition == 2
                        && leaderGroupId != null
                        && !leaderGroupId.trim().isEmpty();

        btnAddGroupAnnouncement.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    private void openAddAnnouncementDialog() {
        if (!"leader".equals(userRole)) return;

        if (leaderGroupId == null || leaderGroupId.trim().isEmpty()) {
            Toast.makeText(requireContext(), "No group is assigned to this leader.", Toast.LENGTH_SHORT).show();
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.dialog_add_group_announcement, null);

        EditText etTitle = dialogView.findViewById(R.id.etAnnouncementTitle);
        EditText etMessage = dialogView.findViewById(R.id.etAnnouncementMessage);

        new AlertDialog.Builder(requireContext())
                .setTitle("Add Group Announcement")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Post", (dialog, which) -> {
                    String title = etTitle.getText().toString().trim();
                    String message = etMessage.getText().toString().trim();

                    if (title.isEmpty() || message.isEmpty()) {
                        Toast.makeText(requireContext(), "Please fill in both title and message.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    postGroupAnnouncement(title, message);
                })
                .show();
    }

    private void postGroupAnnouncement(String title, String message) {
        if (leaderGroupId == null || leaderGroupId.trim().isEmpty()) {
            Toast.makeText(requireContext(), "Leader group could not be found.", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users")
                .document(leaderDocId)
                .get()
                .addOnSuccessListener(doc -> {
                    String firstName = doc.getString("firstName");
                    String lastName = doc.getString("lastName");
                    String leaderName = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();

                    Map<String, Object> newAnnouncement = new HashMap<>();
                    newAnnouncement.put("title", title);
                    newAnnouncement.put("message", message);
                    newAnnouncement.put("groupId", leaderGroupId);
                    newAnnouncement.put("leaderId", leaderDocId);
                    newAnnouncement.put("leaderName", leaderName);
                    newAnnouncement.put("isActive", true);
                    newAnnouncement.put("createdAt", FieldValue.serverTimestamp());

                    db.collection("group_announcements")
                            .add(newAnnouncement)
                            .addOnSuccessListener(ref -> {
                                Toast.makeText(requireContext(), "Announcement posted.", Toast.LENGTH_SHORT).show();
                                vm.refresh(leaderGroupId);
                            })
                            .addOnFailureListener(e ->
                                    Toast.makeText(requireContext(), "Failed to post announcement.", Toast.LENGTH_SHORT).show()
                            );
                })
                .addOnFailureListener(e ->
                        Toast.makeText(requireContext(), "Leader information could not be loaded.", Toast.LENGTH_SHORT).show()
                );
    }

    private void bindThisWeekData(ThisWeekResponse data) {
        tvHeroTitle.setText("leader".equals(userRole) ? "Leader Announcements" : "Announcements");
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