package dev.thor.agent

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Shared layout for credential forms, including Thor's short lower display. */
internal class SettingsForm(context:Context) {
    val ui=SettingsUi(context)
    val compact=context.resources.configuration.let {it.screenWidthDp>it.screenHeightDp && it.screenHeightDp<600}
    val root=ui.column()
    val rail:LinearLayout
    val main:LinearLayout
    init {
        if(compact) {
            val columns=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL}
            rail=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(0,0,ui.dp(14),0)}
            main=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
            columns.addView(rail,LinearLayout.LayoutParams(0,-2,.36f))
            columns.addView(main,LinearLayout.LayoutParams(0,-2,.64f))
            root.addView(columns)
        } else {rail=root;main=root}
    }
    fun section(parent:LinearLayout,title:String):LinearLayout {
        if(compact)parent.addView(ui.text(title,13f,true).apply {setPadding(ui.dp(6),ui.dp(8),0,ui.dp(8))}) else ui.heading(parent,title)
        return LinearLayout(parent.context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(14))
            ui.card(parent).addView(this)
        }
    }
    fun note(parent:LinearLayout,value:String)=ui.text(value,13f,true).also {it.setPadding(0,ui.dp(6),0,ui.dp(6));parent.addView(it)}
    fun field(parent:LinearLayout,label:String,view:View) {
        parent.addView(ui.text(label,14f).apply {setPadding(0,ui.dp(6),0,ui.dp(2))})
        move(parent,view)
    }
    fun move(parent:LinearLayout,view:View) {
        (view.parent as? ViewGroup)?.removeView(view)
        when(view) {
            is EditText->{ui.styleForm(view);view.textSize=15f;view.setPadding(ui.dp(2),ui.dp(8),ui.dp(2),ui.dp(8))}
            is CompoundButton->{view.setTextColor(ui.ink);view.textSize=14f;view.buttonTintList=ColorStateList.valueOf(ui.accent);view.minimumHeight=ui.dp(44)}
            is Button->{view.isAllCaps=false;view.textSize=14f;view.setTextColor(ui.accent);view.backgroundTintList=null;view.background=ui.button("",{}).background;view.minimumHeight=ui.dp(44)}
            is Spinner->{view.minimumHeight=ui.dp(48);view.backgroundTintList=ColorStateList.valueOf(ui.accent)}
            is TextView->{view.setTextColor(ui.muted);view.textSize=13f;view.setLineSpacing(ui.dp(3).toFloat(),1f)}
        }
        parent.addView(view,LinearLayout.LayoutParams(-1,-2))
    }
}
