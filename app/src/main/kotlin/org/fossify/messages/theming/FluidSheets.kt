package org.fossify.messages.theming

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Native draggable sheet instead of alert-list menus.
 *
 * A context action starts from a tangible surface, can be swiped away, and
 * uses the same accent/selection palette as the inbox. Back and outside tap
 * are always cancellable; a selected action fires after dismiss begins.
 */
object FluidSheets {
    private fun px(activity: Activity, dp: Int) =
        (dp * activity.resources.displayMetrics.density + .5f).toInt()

    fun actions(activity: Activity, title: String, actions: Array<String>,
                onSelect: (Int) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = BottomSheetDialog(activity)
        val dark = UiTheme.isDark(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(activity, 18), px(activity, 9),
                px(activity, 18), px(activity, 22))
            background = GradientDrawable().apply {
                setColor(UiTheme.color(activity, "card"))
                cornerRadii = floatArrayOf(
                    px(activity, 28).toFloat(), px(activity, 28).toFloat(),
                    px(activity, 28).toFloat(), px(activity, 28).toFloat(),
                    0f, 0f, 0f, 0f)
            }
        }
        val handle = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = px(activity, 3).toFloat()
                setColor(UiTheme.color(activity, "divider"))
            }
            contentDescription = "Swipe down to close"
        }
        panel.addView(handle, LinearLayout.LayoutParams(px(activity, 36),
            px(activity, 5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = px(activity, 17)
        })
        panel.addView(TextView(activity).apply {
            text = title
            textSize = 21f
            setTextColor(UiTheme.color(activity, "title"))
            setPadding(px(activity, 14), 0, px(activity, 8), px(activity, 15))
            maxLines = 2
        })
        actions.forEachIndexed { index, label ->
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(activity, 55)
                setPadding(px(activity, 16), 0, px(activity, 12), 0)
                background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(UiTheme.color(activity, "selected")),
                    GradientDrawable().apply {
                        cornerRadius = px(activity, 14).toFloat()
                        setColor(Color.TRANSPARENT)
                    }, null)
            }
            val text = TextView(activity).apply {
                this.text = label
                textSize = 16f
                setTextColor(UiTheme.color(activity, "title"))
            }
            item.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(item, LinearLayout.LayoutParams(-1, px(activity, 56)))
            // Selection fires immediately. It never waits for a decorative
            // animation to complete before the requested action executes.
            item.setOnClickListener {
                item.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog.dismiss()
                onSelect(index)
            }
        }
        dialog.setContentView(panel)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.isDraggable = true
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    /** A draggable editing surface. Apply validation returns false to leave
     * the sheet visible with its editor state intact. */
    fun editor(activity: Activity, title: String, content: View,
               onApply: () -> Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = BottomSheetDialog(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(activity, 17), px(activity, 8), px(activity, 17),
                px(activity, 12))
            background = GradientDrawable().apply {
                setColor(UiTheme.color(activity, "card"))
                cornerRadii = floatArrayOf(
                    px(activity, 27).toFloat(),px(activity, 27).toFloat(),
                    px(activity, 27).toFloat(),px(activity, 27).toFloat(),
                    0f,0f,0f,0f)
            }
        }
        panel.addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = px(activity, 3).toFloat()
                setColor(UiTheme.color(activity, "divider"))
            }
            contentDescription = "Swipe down to dismiss"
        },LinearLayout.LayoutParams(px(activity,36),px(activity,5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = px(activity,12)
        })
        panel.addView(TextView(activity).apply{
            text=title
            textSize=20f
            setTextColor(UiTheme.color(activity,"title"))
            setPadding(px(activity,12),0,0,px(activity,8))
        })
        val scroll = object: android.widget.ScrollView(activity){
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                val cap=(resources.displayMetrics.heightPixels*.47f).toInt()
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(
                    cap,View.MeasureSpec.AT_MOST))
            }
        }.apply{
            isFillViewport=false
            clipToPadding=false
            addView(content)
        }
        panel.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        val row=LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity=Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0,px(activity,10),0,px(activity,10))
        }
        fun button(label:String,accent:Boolean,onClick:()->Unit):TextView =
            TextView(activity).apply {
                text=label
                gravity=Gravity.CENTER
                textSize=15f
                setTextColor(UiTheme.color(activity,if(accent)"badge_text" else "title"))
                background=GradientDrawable().apply{
                    setColor(UiTheme.color(activity,if(accent)"accent" else "surface"))
                    cornerRadius=px(activity,15).toFloat()
                }
                setOnClickListener{
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                }
            }
        row.addView(button("Cancel",false){dialog.dismiss()},
            LinearLayout.LayoutParams(0,px(activity,47),1f).apply{
                marginEnd=px(activity,9)
            })
        row.addView(button("Apply",true){
            if(onApply()) dialog.dismiss()
        },LinearLayout.LayoutParams(0,px(activity,47),1f))
        panel.addView(row)
        dialog.setContentView(panel)
        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.isDraggable=true
        }
        dialog.show()
    }

}
