package io.github.kdroidfilter.seforimapp.features.onboarding.licence

import io.github.kdroidfilter.seforimapp.features.onboarding.navigation.OnBoardingDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class LicenceRoutingTest {
    @Test
    fun `an installed Zayit always asks where to install, even with a library already there`() {
        assertEquals(OnBoardingDestination.InstallLocationScreen, nextAfterLicence(isPortable = false, isDatabaseReady = true))
        assertEquals(OnBoardingDestination.InstallLocationScreen, nextAfterLicence(isPortable = false, isDatabaseReady = false))
    }

    @Test
    fun `a copy on a drive goes on as before`() {
        assertEquals(OnBoardingDestination.UserProfilScreen, nextAfterLicence(isPortable = true, isDatabaseReady = true))
        assertEquals(OnBoardingDestination.AvailableDiskSpaceScreen, nextAfterLicence(isPortable = true, isDatabaseReady = false))
    }

    @Test
    fun `installing on this computer skips the install flow only for a ready library`() {
        assertEquals(OnBoardingDestination.UserProfilScreen, nextAfterLocalInstallChoice(isDatabaseReady = true))
        assertEquals(OnBoardingDestination.AvailableDiskSpaceScreen, nextAfterLocalInstallChoice(isDatabaseReady = false))
    }
}
