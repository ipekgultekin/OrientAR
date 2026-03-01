package com.example.orientar.announcements.models;

import java.util.List;

public class ThisWeekEvent {
    public String title;
    public String title_tr;
    public String title_en;

    public String date_text;
    public String time_text;
    public String date_time_iso;

    public String location;
    public String description;

    public List<String> raw_lines;

    public ThisWeekEvent() {}
}