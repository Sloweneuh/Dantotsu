# 3.2.8

- **Features:**
  - Rework of the notification system
    - Updated the settings UI for notifications to use subcategories, to make it more readable
    - Progress notification when Unread Chapters check is in progress
    - Notifications truly work even when the app is closed (If the use of the alarm manager is enabled in settings)
    - Changed the interval settings for subscription checks to match the ones for unread chapters
    - Changed the UI to set a custom interval to use typing in hours or minutes instead of scrolling in minutes
  - Added icons to the extension selection dropdown to make it easier to find the right one when there are many installed

- **Bugfixes:**
  - Fixed an issue that removed filters when a list refreshed
  - Fixed various issues related to using filters alongside name search in lists
  - Fixed an issue causing the display to break if the device screen was off for too long
  - Fixed an issue causing the anime language dropdown to display above the button and move below when scrolling on some devices