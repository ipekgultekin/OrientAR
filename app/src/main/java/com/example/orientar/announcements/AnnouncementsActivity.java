package com.example.orientar.announcements;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class AnnouncementsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String userRole = getIntent().getStringExtra("USER_ROLE");
        String leaderDocId = getIntent().getStringExtra("LEADER_DOC_ID");

        Bundle args = new Bundle();
        args.putString("USER_ROLE", userRole);
        args.putString("LEADER_DOC_ID", leaderDocId);

        AnnouncementsFragment fragment = new AnnouncementsFragment();
        fragment.setArguments(args);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
    }
}