# 3.5.0

- **Features:**
  - Homepage Reordering
    - Homepage sections can now be reordered
  - Updated "List View" of media
    - Now shows synopsis, media status and user progress
    - List view is now available for recommendations in Anilist, MAL and Comick tabs of a media

- **Changes:**
  - Reduced the amount of Anilist API calls to prevent rate limit triggers
  - Made homepage section titles visible during loading
  - Updated default Home section order

- **Bugfixes:**
  - Prevented homepage sections from infinitely loading by putting a placeholder in case of an empty response