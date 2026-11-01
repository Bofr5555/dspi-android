package se.bofr.dspi.bridge

/**
 * [DspiBridgeImpl]'s token issuer: the contract [DangerousOps]
 * (DangerousOpsContract.kt, decisions D1/D2/D5).
 *
 * A token is valid for its own [ConfirmationToken.op] only, on this bridge
 * only (identity, not equality), until it is spent, revoked, or idle for
 * [ttlMs]. Single use, except [DangerousOp.reusable] ([DangerousOp.HARDWARE_APPLY]:
 * one Settings Save applies its whole draft with one token; each use renews
 * its lifetime). A gated bridge member calls [require] before anything is
 * sent to the device; an invalid token throws [DangerousOpNotConfirmed].
 *
 * Tokens are not bound to call arguments (the contract token carries only the
 * op): a gated op needs a fresh, explicit confirmation of that op. The
 * registry is synchronized, so a stray call off the main thread cannot
 * corrupt it.
 */
internal class DangerousOpsImpl(private val clock: () -> Long, override val ttlMs: Long = DEFAULT_TTL_MS) : DangerousOps {
    private class Entry(val token: ConfirmationToken, var lastUseMs: Long)

    private val live = HashMap<Long, Entry>()
    private var nextId = 1L

    @Synchronized
    override fun confirm(op: DangerousOp): ConfirmationToken {
        val t = ConfirmationToken(op, nextId++)
        live[t.id] = Entry(t, clock())
        return t
    }

    @Synchronized
    override fun revoke(token: ConfirmationToken) {
        val e = live[token.id]
        if (e != null && e.token === token) live.remove(token.id)
    }

    @Synchronized
    override fun isValid(token: ConfirmationToken): Boolean = reason(token, token.op) == null

    /** Spend [token] for [op] (renew it, if reusable), or throw [DangerousOpNotConfirmed]. */
    @Synchronized
    fun require(token: ConfirmationToken, op: DangerousOp) {
        reason(token, op)?.let { throw DangerousOpNotConfirmed(op, it) }
        if (op.reusable) live.getValue(token.id).lastUseMs = clock() else live.remove(token.id)
    }

    private fun reason(token: ConfirmationToken, op: DangerousOp): String? {
        if (token.op != op) return "token was confirmed for ${token.op}"
        val e = live[token.id]
        if (e == null || e.token !== token) return "token is spent, revoked or from another bridge"
        if (clock() - e.lastUseMs > ttlMs) {
            live.remove(token.id)
            return "token expired"
        }
        return null
    }

    companion object {
        const val DEFAULT_TTL_MS = 10_000L
    }
}
