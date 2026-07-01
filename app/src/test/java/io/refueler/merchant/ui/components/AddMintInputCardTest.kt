package io.refueler.merchant.ui.components

import android.content.Context
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddMintInputCardTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    private fun createCard(): AddMintInputCard {
        val themedContext = ContextThemeWrapper(appContext, io.refueler.merchant.R.style.Theme_Numo)
        return AddMintInputCard(themedContext)
    }

    @Test
    fun `default presentation matches settings behavior`() {
        val card = createCard()

        val helperText = card.findViewById<TextView>(io.refueler.merchant.R.id.helper_text)
        val urlInput = card.findViewById<EditText>(io.refueler.merchant.R.id.url_input)
        val inlineScan = card.findViewById<ImageButton>(io.refueler.merchant.R.id.scan_button)
        val scanRow = card.findViewById<android.view.View>(io.refueler.merchant.R.id.scan_row_container)
        val addButton = card.findViewById<TextView>(io.refueler.merchant.R.id.add_button)

        assertEquals(android.view.View.VISIBLE, helperText.visibility)
        assertEquals("https://mint.example.com", urlInput.hint.toString())
        assertEquals(android.view.View.VISIBLE, inlineScan.visibility)
        assertEquals(android.view.View.GONE, scanRow.visibility)
        assertEquals(appContext.getColor(io.refueler.merchant.R.color.color_bg_white), addButton.currentTextColor)
    }

    @Test
    fun `onboarding presentation hides helper and inline scan`() {
        val card = createCard()
        card.setOnboardingModeEnabled(true)

        val helperText = card.findViewById<TextView>(io.refueler.merchant.R.id.helper_text)
        val urlInput = card.findViewById<EditText>(io.refueler.merchant.R.id.url_input)
        val inlineScan = card.findViewById<ImageButton>(io.refueler.merchant.R.id.scan_button)
        val scanRow = card.findViewById<android.view.View>(io.refueler.merchant.R.id.scan_row_container)
        val scanRowTitle = card.findViewById<TextView>(io.refueler.merchant.R.id.scan_row_title)
        val scanRowSubtitle = card.findViewById<TextView>(io.refueler.merchant.R.id.scan_row_subtitle)
        val addButton = card.findViewById<TextView>(io.refueler.merchant.R.id.add_button)

        assertEquals(android.view.View.GONE, helperText.visibility)
        assertEquals("Enter mint address", urlInput.hint.toString())
        assertEquals(android.view.View.GONE, inlineScan.visibility)
        assertEquals(android.view.View.VISIBLE, scanRow.visibility)
        assertEquals("Scan QR Code", scanRowTitle.text.toString())
        assertEquals("Tap to scan an address", scanRowSubtitle.text.toString())
        assertEquals(appContext.getColor(io.refueler.merchant.R.color.color_text_primary), addButton.currentTextColor)
    }

    @Test
    fun `onboarding scan row click triggers scan callback`() {
        val card = createCard()
        card.setOnboardingModeEnabled(true)
        card.setMintUrl("mint.coinos.io")

        var scanInvoked = false
        card.setOnAddMintListener(object : AddMintInputCard.OnAddMintListener {
            override fun onAddMint(mintUrl: String) = Unit
            override fun onScanQR() {
                scanInvoked = true
            }
        })

        val scanRow = card.findViewById<android.view.View>(io.refueler.merchant.R.id.scan_row_container)
        scanRow.performClick()

        assertTrue(scanInvoked)
    }
}
