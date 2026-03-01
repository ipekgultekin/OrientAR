package com.example.orientar.announcements.models;

import java.util.List;

public class ThisWeekResponse {
    public String page_title;
    public String source_url;
    public String updated_at;
    public String week_range_text;
    public List<ThisWeekEvent> events;

    public ThisWeekResponse() {}
}