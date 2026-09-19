package eu.kanade.tachiyomi.ui.more

import eu.kanade.tachiyomi.data.updater.AppUpdateStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppUpdateScreenStateTest {
    @Test
    fun `restoring an installed release never offers download after metadata cleanup`() {
        for (stored in AppUpdateStage.entries + listOf(null)) {
            assertEquals(
                NewUpdateScreenModel.Stage.Installed,
                appUpdateScreenStage(stored, preparing = false, installed = true),
            )
            assertEquals(
                NewUpdateScreenModel.Stage.Installed,
                appUpdateScreenStage(stored, preparing = true, installed = true),
            )
        }
    }

    @Test
    fun `an uninstalled release retains available downloaded and verification actions`() {
        assertEquals(
            NewUpdateScreenModel.Stage.Available,
            appUpdateScreenStage(null, preparing = false, installed = false),
        )
        assertEquals(
            NewUpdateScreenModel.Stage.Downloaded,
            appUpdateScreenStage(AppUpdateStage.DOWNLOADED, preparing = false, installed = false),
        )
        assertEquals(
            NewUpdateScreenModel.Stage.Verifying,
            appUpdateScreenStage(AppUpdateStage.DOWNLOADED, preparing = true, installed = false),
        )
    }
}
