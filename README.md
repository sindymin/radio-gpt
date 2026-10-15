# Radio Việt Nam — Android 6

Project source for building a debug APK. Minimum Android version: Android 6.0 (API 23).

## Build

The GitHub Actions workflow at `.github/workflows/android.yml` builds `app-debug.apk` when run manually or when changes are pushed to the `main` branch.

## Important limitations

- `RadioPlayer` uses AndroidX Media3 ExoPlayer and includes HLS support.
- `StationRepository.java` still contains blank stream URLs for VOH-M, Đài Bình Dương, VOV1, VOV2 and VOV3. Those stations will not play until valid audio stream URLs are supplied.
- The current project does not yet implement a resolver to refresh the temporary Đài Bình Dương stream URL.
- The APK has not been built or tested on a device in this environment.
