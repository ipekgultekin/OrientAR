package com.example.orientar.announcements;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.orientar.R;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class AnnouncementNotificationManager {

    private static final String CHANNEL_ID = "orientar_announcements";
    private static final String CHANNEL_NAME = "OrientAR Announcements";

    private final Context context;
    private final FirebaseFirestore db;

    private ListenerRegistration formalListener;
    private ListenerRegistration groupListener;
    private ListenerRegistration thisWeekListener;

    private boolean formalInitialLoaded = false;
    private boolean groupInitialLoaded = false;
    private boolean thisWeekInitialLoaded = false;

    public AnnouncementNotificationManager(Context context) {
        this.context = context.getApplicationContext();
        this.db = FirebaseFirestore.getInstance();
        createNotificationChannel();
    }

    public void startListening(String groupId, boolean isGuest) {
        listenFormalAnnouncements();
        listenThisWeekOnCampus();

        if (!isGuest && groupId != null && !groupId.trim().isEmpty()) {
            listenGroupAnnouncements(groupId);
        }
    }

    public void stopListening() {
        if (formalListener != null) formalListener.remove();
        if (groupListener != null) groupListener.remove();
        if (thisWeekListener != null) thisWeekListener.remove();
    }

    private void listenFormalAnnouncements() {
        formalListener = db.collection("formal_announcements")
                .whereEqualTo("isActive", true)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;

                    if (!formalInitialLoaded) {
                        formalInitialLoaded = true;
                        return;
                    }

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.ADDED) {
                            showNotification(
                                    "METU NCC - New Formal Announcement",
                                    "A new formal announcement has been published."
                            );
                            break;
                        }
                    }
                });
    }

    private void listenGroupAnnouncements(String groupId) {
        groupListener = db.collection("group_announcements")
                .whereEqualTo("isActive", true)
                .whereEqualTo("groupId", groupId)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;

                    if (!groupInitialLoaded) {
                        groupInitialLoaded = true;
                        return;
                    }

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.ADDED) {
                            showNotification(
                                    "New Orientation Group Announcement",
                                    "You have 1 new announcement from your Orientation Leader!"
                            );
                            break;
                        }
                    }
                });
    }

    private void listenThisWeekOnCampus() {
        thisWeekListener = db.collection("campus_events_weeks")
                .document("this-week-on-campus")
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    if (!thisWeekInitialLoaded) {
                        thisWeekInitialLoaded = true;
                        return;
                    }

                    showNotification(
                            "This Week on Campus Updated!",
                            "Ready to discover this week’s campus events?"
                    );
                });
    }

    private void showNotification(String title, String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.metu_logo)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true);

        NotificationManagerCompat.from(context)
                .notify((int) System.currentTimeMillis(), builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            NotificationManager manager =
                    context.getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}