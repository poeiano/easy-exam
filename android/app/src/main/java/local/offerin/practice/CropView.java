package local.offerin.practice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public final class CropView extends View {
    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF image = new RectF(), selection = new RectF();
    private float startX, startY;
    public CropView(Context ctx, Bitmap b) { super(ctx); bitmap = b; setBackgroundColor(Color.rgb(22, 29, 44)); }
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float scale = Math.min((float)getWidth() / bitmap.getWidth(), (float)getHeight() / bitmap.getHeight());
        float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
        image.set((getWidth()-w)/2, (getHeight()-h)/2, (getWidth()+w)/2, (getHeight()+h)/2);
        paint.setAlpha(255); c.drawBitmap(bitmap, null, image, paint);
        if (!selection.isEmpty()) { paint.setColor(Color.rgb(70, 126, 255)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4); c.drawRect(selection, paint); paint.setStyle(Paint.Style.FILL); }
    }
    public boolean onTouchEvent(MotionEvent e) {
        float x = Math.max(image.left, Math.min(image.right, e.getX())), y = Math.max(image.top, Math.min(image.bottom, e.getY()));
        if (e.getAction() == MotionEvent.ACTION_DOWN) { startX = x; startY = y; getParent().requestDisallowInterceptTouchEvent(true); }
        selection.set(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y)); invalidate(); return true;
    }
    public Bitmap crop() {
        if (selection.width() < 15 || selection.height() < 15 || image.isEmpty()) throw new IllegalArgumentException("请先拖动框选题目区域");
        float scale = bitmap.getWidth() / image.width();
        int x = Math.max(0, (int)((selection.left-image.left)*scale)), y = Math.max(0,(int)((selection.top-image.top)*scale));
        int w = Math.min(bitmap.getWidth()-x, Math.max(1,(int)(selection.width()*scale))), h = Math.min(bitmap.getHeight()-y,Math.max(1,(int)(selection.height()*scale)));
        return Bitmap.createBitmap(bitmap,x,y,w,h);
    }
}
