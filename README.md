# OrientAR

## Project Overview

OrientAR is an Augmented Reality–based mobile application designed to assist new students during the orientation process at **METU Northern Cyprus Campus**.

The main goal of the application is to help incoming students explore the campus more easily and reduce the stress of adapting to a new environment.

The system combines **Augmented Reality (AR)**, gamification, navigation tools, and an AI-powered chatbot.

---

### Main Features

- AR-based campus navigation  
- Treasure hunt style orientation game  
- AI chatbot for frequently asked questions  
- Campus announcements and orientation events  
- Student societies information  
- Orientation group management  

---

## Build Scripts

If you receive the **OrientAR-Mobile** project as a ZIP file, extract the project folder and open it using the latest stable version of **Android Studio**.

When the project is opened, Android Studio will automatically detect the Gradle configuration files. Click **Sync Now** to download dependencies.

---

### API Key Setup

Create the following file:

```
res/values/google_maps_api.xml
```


Insert your **Google Maps SDK API Key** and **ARCore Geospatial API Key**.

---

### Firebase Setup

Place the following file inside the `app/` directory:

```
google-services.json
```

Enable **Firebase Authentication** and **Cloud Firestore**.

---

### Running the Application

1. Connect an **ARCore-supported Android device**
2. Enable **USB debugging**
3. Click **Run** in Android Studio

⚠️ The project cannot be fully tested on an emulator because AR features require camera input and real-world tracking.

The project is currently under development and has not yet been published on the Google Play Store.
