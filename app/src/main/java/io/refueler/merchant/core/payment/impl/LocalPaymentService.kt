package io.refueler.merchant.core.payment.impl

import io.refueler.merchant.core.payment.PaymentData
import io.refueler.merchant.core.payment.IPaymentService
import io.refueler.merchant.core.payment.PaymentState
import io.refueler.merchant.core.payment.RedeemResult
import io.refueler.merchant.core.util.MintManager
import io.refueler.merchant.core.wallet.QuoteStatus
import io.refueler.merchant.core.wallet.Satoshis
import io.refueler.merchant.core.wallet.WalletProvider
import io.refueler.merchant.core.wallet.WalletResult

/**
 * [IPaymentService] backed by the local CDK wallet.
 *
 * Pure delegation — no new business logic. The existing Cashu/Lightning flows
 * in [io.refueler.merchant.payment.LightningMintHandler] and
 * [io.refueler.merchant.ndef.CashuPaymentHelper] continue to work unchanged.
 */
class LocalPaymentService(
    private val walletProvider: WalletProvider,
    private val mintManager: MintManager
) : IPaymentService {

    override suspend fun createPayment(
        amountSats: Long,
        description: String?,
        posCartJson: String?,
    ): WalletResult<PaymentData> {
        val mintUrl = mintManager.getPreferredLightningMint()
            ?: return WalletResult.Failure(
                io.refueler.merchant.core.wallet.WalletError.NotInitialized(
                    "No preferred Lightning mint configured"
                )
            )

        return walletProvider.requestMintQuote(
            mintUrl = mintUrl,
            amount = Satoshis(amountSats),
            description = description
        ).map { quote ->
            PaymentData(
                paymentId = quote.quoteId,
                bolt11 = quote.bolt11Invoice,
                cashuPR = null, // Local mode: Numo builds its own creq
                mintUrl = mintUrl,
                expiresAt = quote.expiryTimestamp
            )
        }
    }

    override suspend fun checkPaymentStatus(paymentId: String): WalletResult<PaymentState> {
        val mintUrl = mintManager.getPreferredLightningMint()
            ?: return WalletResult.Failure(
                io.refueler.merchant.core.wallet.WalletError.NotInitialized(
                    "No preferred Lightning mint configured"
                )
            )

        return walletProvider.checkMintQuote(mintUrl, paymentId).map { status ->
            when (status.status) {
                QuoteStatus.UNPAID -> PaymentState.PENDING
                QuoteStatus.PENDING -> PaymentState.PENDING
                QuoteStatus.PAID -> PaymentState.PENDING
                QuoteStatus.ISSUED -> PaymentState.PAID
                QuoteStatus.EXPIRED -> PaymentState.EXPIRED
                QuoteStatus.UNKNOWN -> PaymentState.FAILED
            }
        }
    }

    override suspend fun redeemToken(
        token: String,
        paymentId: String?
    ): WalletResult<RedeemResult> {
        // paymentId is ignored for local mode
        return walletProvider.receiveToken(token).map { result ->
            RedeemResult(
                amount = result.amount,
                proofsCount = result.proofsCount
            )
        }
    }

    override fun isReady(): Boolean = walletProvider.isReady()
}
