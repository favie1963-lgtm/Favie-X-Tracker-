package com.favie.xtracker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ScreenCaptureService}'s projection-lifetime rule.
 *
 * The service cannot be exercised end to end without a device and a real
 * {@code MediaProjection}, so what is pinned here is the one piece of logic that
 * decides whether a delivered {@code onStop} means "the user's capture ended".
 * Getting it wrong is exactly the failure that made the app stop capturing on its
 * own: the engine stops the previous projection when a new consent replaces it, and
 * the platform then delivers that superseded projection's stop *after* the new
 * projection is attached. If any stop is taken at face value, the new session is
 * torn down the instant it starts.
 */
public class ScreenCaptureServiceTest {

    /**
     * A stop for the projection that is currently attached is a real end of capture.
     */
    @Test
    public void stopForTheLiveProjectionIsReal() {
        Object live = new Object();
        assertTrue(ScreenCaptureService.stopBelongsToLiveProjection(live, live));
    }

    /**
     * A stop for a projection that a newer consent has already replaced must be
     * ignored, or a re-grant kills the session it just created.
     */
    @Test
    public void stopForASupersededProjectionIsIgnored() {
        Object live = new Object();
        Object superseded = new Object();
        assertFalse(ScreenCaptureService.stopBelongsToLiveProjection(live, superseded));
    }

    /**
     * With no projection attached there is no session to end, so no stop counts.
     */
    @Test
    public void stopWithNothingAttachedIsIgnored() {
        assertFalse(ScreenCaptureService.stopBelongsToLiveProjection(null, new Object()));
        assertFalse(ScreenCaptureService.stopBelongsToLiveProjection(null, null));
    }
}
