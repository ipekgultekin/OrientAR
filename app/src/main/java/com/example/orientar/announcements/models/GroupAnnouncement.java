package com.example.orientar.announcements.models;

import com.google.firebase.Timestamp;

public class GroupAnnouncement {
    public String id;
    public String title;
    public String message;
    public String groupId;
    public String leaderId;
    public String leaderName;
    public boolean isActive;
    public Timestamp createdAt;

    public GroupAnnouncement() {}
}