package org.fossify.messages.helpers

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/** Shared keyboard contract for normal, floating and private message composers. */
object MessageComposer {
    fun configure(input:EditText,sendOnEnter:Boolean,onSend:()->Unit) {
        input.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if(sendOnEnter)0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        input.imeOptions=if(sendOnEnter)EditorInfo.IME_ACTION_SEND else EditorInfo.IME_ACTION_NONE
        input.setOnEditorActionListener { _,action,_ ->
            if(sendOnEnter && action==EditorInfo.IME_ACTION_SEND){onSend();true} else false
        }
        input.setOnKeyListener { _,key,event ->
            if(sendOnEnter && key==KeyEvent.KEYCODE_ENTER){
                if(event.action==KeyEvent.ACTION_UP)onSend()
                true
            } else false
        }
    }
}
