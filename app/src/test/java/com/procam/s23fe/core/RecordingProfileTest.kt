package com.procam.s23fe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingProfileTest {

    @Test fun fhd60_is_locked_to_60() {
        val r = RecordingProfile.FHD_HIGH.aeFpsRange()
        assertEquals(60, r.lower)
        assertEquals(60, r.upper)
    }

    @Test fun fhd60_is_100Mbps() {
        assertEquals(100_000_000, RecordingProfile.FHD_HIGH.videoBitrate)
    }

    @Test fun all_profiles_force_stereo_48k_256k_audio() {
        for (p in RecordingProfile.entries) {
            assertEquals(2, p.audioChannels)
            assertEquals(48_000, p.audioSampleRate)
            assertEquals(256_000, p.audioBitrate)
        }
    }
}
