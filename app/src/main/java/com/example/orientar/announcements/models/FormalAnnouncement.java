package com.example.orientar.announcements.models;

import com.google.firebase.Timestamp;

public class FormalAnnouncement {
    public String id;
    public String title;
    public String message;
    public String target;
    public String createdBy;
    public boolean isActive;
    public Timestamp createdAt;
}