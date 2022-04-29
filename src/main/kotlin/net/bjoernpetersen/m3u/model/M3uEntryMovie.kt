package net.bjoernpetersen.m3u.model

import java.time.Duration

class M3uEntryMovie(
    location: MediaLocation,
    duration: Duration? = null,
    title: String? = null,
    val metadata: M3uMetadata = M3uMetadata.empty()
): M3uEntry(location, duration, title)
