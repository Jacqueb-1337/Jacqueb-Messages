package org.fossify.messages.messaging

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.fossify.messages.theming.UiTheme

/** Visual progress only: Android's MMS transport has no byte-level callback. */
object MmsDownloadProgressView {
    fun create(ctx:Context,id:Long,onRetry:()->Unit):View {
        fun dp(i:Int)=(i*ctx.resources.displayMetrics.density+0.5f).toInt()
        val state=PendingMmsDownloads.display(id)
        if(state==null || state.phase==PendingMmsDownloads.Phase.FAILED) {
            return TextView(ctx).apply {
                text=if(state?.phase==PendingMmsDownloads.Phase.FAILED)
                    "Download failed • Retry" else "↓  Download MMS"
                contentDescription=text.toString()
                setTextColor(UiTheme.color(ctx,"incoming_text"))
                textSize=15f
                minHeight=dp(44)
                gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(12),dp(7),dp(12),dp(7))
                isClickable=true
                isFocusable=true
                setOnClickListener{onRetry()}
            }
        }
        val elapsed=SystemClock.elapsedRealtime()-state.startedAt
        val doneElapsed=SystemClock.elapsedRealtime()-state.finishedAt
        val finished=state.phase==PendingMmsDownloads.Phase.FINISHING
        val label=when {
            finished -> "Download complete"
            state.phase==PendingMmsDownloads.Phase.PROCESSING -> "Processing MMS…"
            elapsed<1200 -> "Connecting to carrier…"
            else -> "Downloading MMS…"
        }
        val column=LinearLayout(ctx).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(9),dp(10),dp(9))
            isClickable=false
            contentDescription=label+", progress animation; no measured transfer percentage"
        }
        column.addView(TextView(ctx).apply {
            text=label
            textSize=14f
            setTextColor(UiTheme.color(ctx,"incoming_text"))
        },LinearLayout.LayoutParams(-1,-2))
        val bar=ProgressBar(ctx,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100
            progressTintList=ColorStateList.valueOf(UiTheme.color(ctx,"accent"))
            isIndeterminate=false
            isClickable=false
            contentDescription="Animated MMS download progress"
        }
        column.addView(bar,LinearLayout.LayoutParams(dp(220),dp(6)).apply{topMargin=dp(9)})
        // No misleading "10%" text: this is an elapsed-time animation only.
        val now=if(finished) (10+(doneElapsed*90/850).toInt()).coerceIn(10,100)
            else ((elapsed*10/8000).toInt()).coerceIn(0,10)
        bar.progress=now
        val anim=ValueAnimator.ofInt(now,if(finished)100 else 10).apply {
            duration=if(finished)(850-doneElapsed).coerceAtLeast(1)
                else (8000-elapsed).coerceAtLeast(1)
            addUpdateListener{bar.progress=it.animatedValue as Int}
        }
        column.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View){anim.start()}
            override fun onViewDetachedFromWindow(v:View){anim.cancel()}
        })
        if(finished){
            column.alpha=1f
            column.animate().alpha(0f).setStartDelay((1200-doneElapsed).coerceAtLeast(0))
                .setDuration(300).start()
        }
        return column
    }
}
