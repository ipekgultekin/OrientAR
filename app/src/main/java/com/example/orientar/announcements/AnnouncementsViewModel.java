package com.example.orientar.announcements;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.orientar.announcements.models.ThisWeekResponse;

public class AnnouncementsViewModel extends ViewModel {

    public static class UiState {
        public boolean loading;
        public String error;
        public ThisWeekResponse data;

        public UiState(boolean loading, String error, ThisWeekResponse data) {
            this.loading = loading;
            this.error = error;
            this.data = data;
        }
    }

    private final MutableLiveData<UiState> state =
            new MutableLiveData<>(new UiState(false, null, null));

    private final AnnouncementsRepository repo = new AnnouncementsRepository();

    public LiveData<UiState> getState() {
        return state;
    }

    public void refresh() {
        UiState cur = state.getValue();
        state.setValue(new UiState(true, null, cur != null ? cur.data : null));

        repo.fetchThisWeekOnCampus(new AnnouncementsRepository.Callback() {
            @Override
            public void onSuccess(ThisWeekResponse data) {
                state.postValue(new UiState(false, null, data));
            }

            @Override
            public void onError(String message) {
                UiState cur2 = state.getValue();
                state.postValue(new UiState(false, message, cur2 != null ? cur2.data : null));
            }
        });
    }
}