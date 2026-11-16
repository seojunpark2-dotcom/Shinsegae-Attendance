# Shinsegae Attendance project instructions

- Make changes and publish releases on `log` only. Do not modify other branches unless the user explicitly changes this preference.
- Use semantic versioning: increment major for incompatible changes, minor for backward-compatible features, and patch for backward-compatible fixes. Reset patch to zero when minor increases, and reset minor and patch to zero when major increases. Preserve published versions as history.
- Increase Android `versionCode` on every release. Keep `versionName`, APK and ZIP filenames, and the README download links aligned. Preserve older releases as history.
- Never commit account credentials, device unlock secrets, local Android SDK paths, or attachment files.
