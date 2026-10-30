package org.fossify.messages.safe

import android.app.Activity
import android.app.Dialog
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Color
import android.view.*
import android.widget.*

/** Displays decrypted bytes only in memory, with the same Safe session and secure window. */
class SafeImageViewer(activity:Activity, bytes:ByteArray):Dialog(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val image=ZoomImage(activity)
    init {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root=FrameLayout(activity).apply{setBackgroundColor(Color.BLACK)}
        root.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(Button(activity).apply{text="Close";contentDescription="Close private image";setOnClickListener{dismiss()}},FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        setContentView(root)
        com.bumptech.glide.Glide.with(activity).load(bytes)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)
            .into(image)
        setOnDismissListener {com.bumptech.glide.Glide.with(activity).clear(image);image.setImageDrawable(null)}
    }
    override fun onStart(){super.onStart();window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);window?.setLayout(-1,-1)}
    private class ZoomImage(context:android.content.Context):androidx.appcompat.widget.AppCompatImageView(context) {
        private val transform=Matrix()
        private var zoom=1f
        private var lastX=0f;private var lastY=0f
        private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(d:ScaleGestureDetector):Boolean{scale(d.scaleFactor,d.focusX,d.focusY);return true}
        })
        private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onDoubleTap(e:MotionEvent):Boolean{if(zoom>1.1f)fit() else scale(3f,e.x,e.y);return true}
        })
        init {scaleType=ScaleType.MATRIX;contentDescription="Private image. Pinch or double tap to zoom."}
        override fun setImageDrawable(value:android.graphics.drawable.Drawable?){super.setImageDrawable(value);post{fit()}}
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);fit()}
        private fun fit(){
            val d=drawable?:return;if(width==0||height==0||d.intrinsicWidth<=0||d.intrinsicHeight<=0)return
            val factor=minOf(width.toFloat()/d.intrinsicWidth,height.toFloat()/d.intrinsicHeight)
            transform.setScale(factor,factor);transform.postTranslate((width-d.intrinsicWidth*factor)/2,(height-d.intrinsicHeight*factor)/2)
            zoom=1f;imageMatrix=transform
        }
        private fun scale(factor:Float,x:Float,y:Float){val next=(zoom*factor).coerceIn(1f,8f);transform.postScale(next/zoom,next/zoom,x,y);zoom=next;constrain()}
        private fun constrain(){
            val d=drawable?:return
            val rect=RectF(0f,0f,d.intrinsicWidth.toFloat(),d.intrinsicHeight.toFloat());transform.mapRect(rect)
            val dx=if(rect.width()<=width)(width-rect.width())/2-rect.left else if(rect.left>0)-rect.left else if(rect.right<width)width-rect.right else 0f
            val dy=if(rect.height()<=height)(height-rect.height())/2-rect.top else if(rect.top>0)-rect.top else if(rect.bottom<height)height-rect.bottom else 0f
            transform.postTranslate(dx,dy);imageMatrix=transform
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            gestures.onTouchEvent(e);scaler.onTouchEvent(e)
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{lastX=e.x;lastY=e.y}
                MotionEvent.ACTION_MOVE->{if(!scaler.isInProgress && e.pointerCount==1 && zoom>1f){transform.postTranslate(e.x-lastX,e.y-lastY);constrain()};lastX=e.x;lastY=e.y}
                MotionEvent.ACTION_POINTER_UP->{val index=if(e.actionIndex==0)1 else 0;if(index<e.pointerCount){lastX=e.getX(index);lastY=e.getY(index)}}
            }
            return true
        }
    }
}
