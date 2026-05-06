package com.example.orientar.announcements;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.orientar.announcements.models.FormalAnnouncement;
import com.example.orientar.announcements.models.GroupAnnouncement;
import com.example.orientar.announcements.models.ThisWeekResponse;

import java.util.ArrayList;
import java.util.List;

public class AnnouncementsViewModel extends ViewModel {
    private static final String TAG_ANN = "GA_ANN";

    public static class UiState {
        public boolean loading;
        public String error;
        public ThisWeekResponse thisWeekData;
        public List<FormalAnnouncement> formalAnnouncements;
        public List<GroupAnnouncement> groupAnnouncements;

        public UiState(boolean loading,
                       String error,
                       ThisWeekResponse thisWeekData,
                       List<FormalAnnouncement> formalAnnouncements,
                       List<GroupAnnouncement> groupAnnouncements) {
            this.loading = loading;
            this.error = error;
            this.thisWeekData = thisWeekData;
            this.formalAnnouncements = formalAnnouncements;
            this.groupAnnouncements = groupAnnouncements;
        }
    }

    private final MutableLiveData<UiState> state =
            new MutableLiveData<>(new UiState(
                    false,
                    null,
                    null,
                    new ArrayList<>(),
                    new ArrayList<>()
            ));

    private final AnnouncementsRepository repo = new AnnouncementsRepository();

    public LiveData<UiState> getState() {
        return state;
    }

    public void refresh(String groupId) {
        Log.d(TAG_ANN, "Announcements refresh started. groupId=" + groupId);
        UiState cur = state.getValue();

        state.setValue(new UiState(
                true,
                null,
                cur != null ? cur.thisWeekData : null,
                cur != null ? cur.formalAnnouncements : new ArrayList<>(),
                cur != null ? cur.groupAnnouncements : new ArrayList<>()
        ));

        repo.fetchFormalAnnouncements(new AnnouncementsRepository.FormalCallback() {
            @Override
            public void onSuccess(List<FormalAnnouncement> formalData) {
                repo.fetchGroupAnnouncements(groupId, new AnnouncementsRepository.GroupCallback() {
                    @Override
                    public void onSuccess(List<GroupAnnouncement> groupData) {
                        repo.fetchThisWeekOnCampus(new AnnouncementsRepository.Callback() {
                            @Override
                            public void onSuccess(ThisWeekResponse thisWeekData) {
                                Log.d(TAG_ANN, "Announcements refresh completed. formalCount=" + formalData.size()
                                        + ", groupCount=" + groupData.size()
                                        + ", thisWeekCount=" + (thisWeekData.events == null ? 0 : thisWeekData.events.size()));
                                state.postValue(new UiState(
                                        false,
                                        null,
                                        thisWeekData,
                                        formalData,
                                        groupData
                                ));
                            }

                            @Override
                            public void onError(String message) {
                                state.postValue(new UiState(
                                        false,
                                        message,
                                        null,
                                        formalData,
                                        groupData
                                ));
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        UiState cur2 = state.getValue();
                        state.postValue(new UiState(
                                false,
                                message,
                                cur2 != null ? cur2.thisWeekData : null,
                                cur2 != null ? cur2.formalAnnouncements : new ArrayList<>(),
                                cur2 != null ? cur2.groupAnnouncements : new ArrayList<>()
                        ));
                    }
                });
            }

            @Override
            public void onError(String message) {
                UiState cur2 = state.getValue();
                state.postValue(new UiState(
                        false,
                        message,
                        cur2 != null ? cur2.thisWeekData : null,
                        cur2 != null ? cur2.formalAnnouncements : new ArrayList<>(),
                        cur2 != null ? cur2.groupAnnouncements : new ArrayList<>()
                ));
            }
        });
    }
}