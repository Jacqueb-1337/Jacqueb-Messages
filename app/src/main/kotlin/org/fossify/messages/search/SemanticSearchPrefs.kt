package org.fossify.messages.search

import android.content.Context

/**
 * Separate permission to opt into future on-device semantic search.
 * Ordinary search never depends on this state or downloads a model.
 * The engine does not run until it is actually installed and verified.
 */
object SemanticSearchPrefs {
    private const val PREFS = "messages_semantic_search"
    private const val STATUS = "offer_state"
    private const val ASK = "ask"
    private const val DISMISSED = "dismissed"
    private const val OPTED_IN = "opted_in"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldOffer(ctx: Context) = prefs(ctx).getString(STATUS, ASK) == ASK
    fun optedIn(ctx: Context) = prefs(ctx).getString(STATUS, ASK) == OPTED_IN
    fun dismissed(ctx: Context) = prefs(ctx).getString(STATUS, ASK) == DISMISSED
    fun optIn(ctx: Context) { prefs(ctx).edit().putString(STATUS, OPTED_IN).apply() }
    fun dismiss(ctx: Context) { prefs(ctx).edit().putString(STATUS, DISMISSED).apply() }
    fun reset(ctx: Context) { prefs(ctx).edit().putString(STATUS, ASK).apply() }

    fun selected(ctx:Context):SemanticEngine =
        SemanticEngine.byId(prefs(ctx).getString("engine","fast") ?: "fast")
    fun select(ctx:Context,engine:SemanticEngine) {
        prefs(ctx).edit().putString("engine",engine.id).apply()
        SemanticIndex.unload()
        SemanticIndex.clear(ctx)
    }
    fun engineInstalled(ctx:Context):Boolean = SemanticModelManager.installed(ctx,selected(ctx))
    fun disable(ctx:Context) {reset(ctx);SemanticIndex.unload();SemanticIndex.clear(ctx)}
}
