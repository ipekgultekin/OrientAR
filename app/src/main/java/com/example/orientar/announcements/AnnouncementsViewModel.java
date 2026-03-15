package com.example.orientar.announcements;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.orientar.announcements.models.FormalAnnouncement;
import com.example.orientar.announcements.models.ThisWeekResponse;

import java.util.ArrayList;
import java.util.List;

public class AnnouncementsViewModel extends ViewModel {

    public static class UiState {
        public boolean loading;
        public String error;
        public ThisWeekResponse thisWeekData;
        public List<FormalAnnouncement> formalAnnouncements;

        public UiState(boolean loading, String error,
                       ThisWeekResponse thisWeekData,
                       List<FormalAnnouncement> formalAnnouncements) {
            this.loading = loading;
            this.error = error;
            this.thisWeekData = thisWeekData;
            this.formalAnnouncements = formalAnnouncements;
        }
    }

    private final MutableLiveData<UiState> state =
            new MutableLiveData<>(new UiState(false, null, null, new ArrayList<>()));

    private final AnnouncementsRepository repo = new AnnouncementsRepository();

    public LiveData<UiState> getState() {
        return state;
    }

    public void refresh() {
        UiState cur = state.getValue();
        state.setValue(new UiState(
                true,
                null,
                cur != null ? cur.thisWeekData : null,
                cur != null ? cur.formalAnnouncements : new ArrayList<>()
        ));

        repo.fetchFormalAnnouncements(new AnnouncementsRepository.FormalCallback() {
            @Override
            public void onSuccess(List<FormalAnnouncement> formalData) {
                repo.fetchThisWeekOnCampus(new AnnouncementsRepository.Callback() {
                    @Override
                    public void onSuccess(ThisWeekResponse thisWeekData) {
                        state.postValue(new UiState(false, null, thisWeekData, formalData));
                    }

                    @Override
                    public void onError(String message) {
                        state.postValue(new UiState(false, message, null, formalData));
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
                        cur2 != null ? cur2.formalAnnouncements : new ArrayList<>()
                ));
            }
        });
    }
}