package local.offerin.practice;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

final class UiTheme {
    static final int INK=0xff1c2d45,MUTED=0xff65758b,BLUE=0xff315ee9,BG=0xfff3f6fb,BORDER=0xffe5ebf3;
    static int dp(Context c,float n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    static GradientDrawable surface(Context c,int color,int radius,boolean border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(border)d.setStroke(dp(c,1),BORDER);return d;}
    static RippleDrawable interactiveSurface(Context c,int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x14315ee9),surface(c,color,radius,false),null);}
    static Drawable selectSurface(Context c){LayerDrawable layers=new LayerDrawable(new Drawable[]{surface(c,BG,12,true),c.getDrawable(R.drawable.ic_chevron_down)});layers.setLayerSize(1,dp(c,18),dp(c,18));layers.setLayerGravity(1,Gravity.END|Gravity.CENTER_VERTICAL);layers.setLayerInsetRelative(1,0,0,dp(c,12),0);return layers;}
    static void input(EditText input){
        Context c=input.getContext();input.setTextSize(14);input.setTextColor(INK);input.setHintTextColor(0xff98a5b8);input.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));input.setMinHeight(dp(c,50));
        StateListDrawable states=new StateListDrawable();GradientDrawable focused=surface(c,Color.WHITE,12,false);focused.setStroke(dp(c,1),BLUE);states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},surface(c,BG,12,true));input.setBackground(states);
    }
    static AlertDialog.Builder dialog(Context c){return new AlertDialog.Builder(c){
        @Override public AlertDialog show(){AlertDialog d=super.show();Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(surface(c,Color.WHITE,24,false));w.getDecorView().setClipToOutline(true);w.setDimAmount(.32f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);w.setLayout(Math.min(dp(c,420),c.getResources().getDisplayMetrics().widthPixels-dp(c,48)),WindowManager.LayoutParams.WRAP_CONTENT);}for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){Button b=d.getButton(which);if(b!=null){b.setTextColor(BLUE);b.setAllCaps(false);b.setMinHeight(dp(c,48));b.setBackground(interactiveSurface(c,Color.TRANSPARENT,10));}}return d;}
    };}
    static TextView text(Context c,String value,float size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(c,3),1);if(size>=16)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    static Button button(Context c,String label,boolean primary,Runnable action){
        Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setStateListAnimator(null);b.setElevation(0);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff98a5b8,primary?0xffffffff:BLUE}));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x33ffffff:0x14315ee9),surface(c,primary?BLUE:0xffedf2fd,10,false),null));
        b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(c,10),dp(c,10),dp(c,10),dp(c,10));b.setOnClickListener(v->action.run());return b;
    }
}
