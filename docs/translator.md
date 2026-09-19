

## In-app translator updates

Numbered production translator builds check `LegendZ69/mihon` on app launch. A successful check is reused for 24 hours; a failed check retries after one hour. More → Settings → About → Check for updates checks immediately. Published translator prereleases are eligible, with their validation status visible; reservation tags without a published release are never offered.

Choose Download to start one persistent background transfer. The update screen and its notification show progress; leaving the screen does not stop it. Cancel stops the transfer, and Retry restarts incomplete bytes. About → Update download reopens retained progress or a verified APK even when offline. Release-note edits do not invalidate an unchanged APK.

Before installation, the app checks the release manifest, byte count, SHA-256, package, architecture, version code and signing certificate. Failed verification prevents installation. Install opens Android's package installer; if Android has not allowed Mihon to install applications, first grant that setting and then tap Install again. Cancelling the installer keeps the verified download for another attempt. On relaunch, observing the installed target version clears the completed download.

The preserved application ID and signing certificate allow an in-place update without clearing the library, translations or credentials. Older builds with the updater disabled need one initial manual/in-place installation of an updater-capable build. Benchmark and debug translator packages do not offer production self-updates. A successful download, installer launch or CI build is not device acceptance; consult the release's recorded checks.
