package com.example.orientar.announcements;

import com.example.orientar.announcements.models.ThisWeekEvent;
import com.example.orientar.announcements.models.ThisWeekResponse;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnnouncementsRepository {

    public interface Callback {
        void onSuccess(ThisWeekResponse data);
        void onError(String message);
    }

    public void fetchThisWeekOnCampus(Callback cb) {
        FirebaseFirestore.getInstance()
                .collection("campus_events_weeks")
                .document("this-week-on-campus")
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        cb.onError("No announcements found.");
                        return;
                    }
                    cb.onSuccess(parse(doc));
                })
                .addOnFailureListener(e -> cb.onError(
                        e.getMessage() != null ? e.getMessage() : "Unknown error"
                ));
    }

    private ThisWeekResponse parse(DocumentSnapshot doc) {
        ThisWeekResponse res = new ThisWeekResponse();
        res.page_title = safeStr(doc.get("page_title"));
        res.source_url = safeStr(doc.get("source_url"));
        res.updated_at = safeStr(doc.get("updated_at"));
        res.week_range_text = safeStr(doc.get("week_range_text"));

        List<ThisWeekEvent> events = new ArrayList<>();
        List<Map<String, Object>> raw = (List<Map<String, Object>>) doc.get("events");
        if (raw != null) {
            for (Map<String, Object> m : raw) {
                ThisWeekEvent ev = new ThisWeekEvent();
                ev.title = safeStr(m.get("title"));
                ev.title_tr = safeStr(m.get("title_tr"));
                ev.title_en = safeStr(m.get("title_en"));
                ev.date_text = safeStr(m.get("date_text"));
                ev.time_text = safeStr(m.get("time_text"));
                ev.location = safeStr(m.get("location"));
                ev.description = safeStr(m.get("description"));
                ev.date_time_iso = m.get("date_time_iso") == null ? "" : String.valueOf(m.get("date_time_iso"));

                // raw_lines (optional)
                try {
                    ev.raw_lines = (List<String>) m.get("raw_lines");
                } catch (Exception ignored) {}

                events.add(ev);
            }
        }
        res.events = events;
        return res;
    }

    private String safeStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}