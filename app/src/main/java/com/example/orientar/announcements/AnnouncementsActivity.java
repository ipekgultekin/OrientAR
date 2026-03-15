package com.example.orientar.announcements;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class AnnouncementsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, new AnnouncementsFragment())
                .commit();
    }
}