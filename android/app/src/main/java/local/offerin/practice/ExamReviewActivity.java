package local.offerin.practice;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.*;
import org.json.*;
import io.noties.markwon.Markwon;

/** Record viewer; continuation is returned to MainActivity so only one writer owns a session. */
public final class ExamReviewActivity extends Activity {
    private static final int INK=UiTheme.INK,MUTED=UiTheme.MUTED,BLUE=UiTheme.BLUE;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ExamStore store;private JSONObject exam;private String examId;private LinearLayout body;private Markwon markdown;
    private int page,generation;private boolean closed;private byte[] export;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size,int color){TextView t=UiTheme.text(this,value,size,color);t.setPadding(0,dp(7),0,dp(7));return t;}
    private Button button(String title,Runnable action){Button b=UiTheme.button(this,title,title.startsWith("继续"),action);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);lp.bottomMargin=dp(6);b.setLayoutParams(lp);return b;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);markdown=AnswerRenderer.create(this);examId=getIntent().getStringExtra("examId");
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(UiTheme.BG);scroll.setFillViewport(true);FrameLayout holder=new FrameLayout(this);scroll.addView(holder);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(24),dp(16),dp(24));holder.addView(body,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob)->{int width=Math.min(r-l,dp(760));if(width>0&&body.getLayoutParams().width!=width){body.getLayoutParams().width=width;body.requestLayout();}});setContentView(scroll);body.addView(text("正在读取笔试记录…",16,MUTED));
        worker.execute(()->{try{store=new ExamStore(new File(getFilesDir(),"exam-records"));JSONObject loaded=store.read(examId);runOnUiThread(()->{if(closed)return;exam=loaded;render();});}catch(Exception e){runOnUiThread(()->{if(!closed){Toast.makeText(this,"笔试记录无法读取",Toast.LENGTH_LONG).show();finish();}});}});
    }
    public static String state(String value){switch(value){case "complete":return "已完成";case "failed":return "作答失败";case "stopped":return "已停止";case "interrupted":return "中断，已保存已有内容";case "generating":return "作答中";default:return value;}}
    private void render(){
        final int token=++generation;body.removeAllViews();
        body.addView(button("← 返回笔试记录",this::finish));TextView title=text(exam.optString("title"),23,INK);title.setTypeface(null,Typeface.BOLD);body.addView(title);
        String when=exam.optString("legacyAt");if(when.isEmpty())when=DateFormat.getDateTimeInstance().format(new Date(exam.optLong("createdAt")));body.addView(text(when+" · 题图与答案保留",12,MUTED));body.addView(button("继续这份笔试",()->continueQuestion("")));body.addView(button("导出本次笔试文字",this::exportText));
        JSONArray questions=exam.optJSONArray("questions");if(questions==null||questions.length()==0){body.addView(text("本次笔试尚未保存题目。",15,MUTED));return;}
        int from=page*5,to=Math.min(questions.length(),from+5);body.addView(text("第 "+(from+1)+"–"+to+" 题 / 共 "+questions.length()+" 题",14,BLUE));
        if(page>0)body.addView(button("上一页",()->{page--;render();}));
        for(int i=from;i<to;i++){
            JSONObject q=questions.optJSONObject(i);if(q==null)continue;LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(UiTheme.surface(this,Color.WHITE,18,true));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(12);cp.bottomMargin=dp(8);body.addView(card,cp);
            card.addView(text("第 "+(i+1)+" 题 · "+q.optString("source"),18,INK));String image=q.optString("image"),original=q.optString("originalImage");
            card.addView(button("继续解答／追问这道题",()->continueQuestion(q.optString("id"))));
            if(!image.isEmpty()){
                ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("题目图片，点击查看大图");card.addView(preview,new LinearLayout.LayoutParams(-1,dp(210)));loadImage(preview,image,800,token);preview.setOnClickListener(v->showImage(image));
                if(!original.isEmpty()&&!original.equals(image))card.addView(button("查看未裁剪原图",()->showImage(original)));
            }else card.addView(text("这道题仅保存了文字。",12,MUTED));
            JSONArray turns=q.optJSONArray("turns");if(turns==null||turns.length()==0){card.addView(text("图片已保存，尚未解答。",14,MUTED));continue;}
            for(int j=0;j<turns.length();j++){
                JSONObject turn=turns.optJSONObject(j);if(turn==null)continue;card.addView(text((j==0?"首次解答":"追问 "+j)+" · "+state(turn.optString("status")),14,BLUE));
                TextView prompt=text(turn.optString("prompt"),14,INK);prompt.setTextIsSelectable(true);card.addView(prompt);
                String used=turn.optString("image");if(!used.isEmpty()&&!used.equals(image))card.addView(button("查看此解答使用的图片",()->showImage(used)));
                TextView answer=text(turn.optString("answer").isEmpty()?"尚无答案内容":turn.optString("answer"),15,INK);answer.setTextIsSelectable(true);if(!turn.optString("answer").isEmpty())AnswerRenderer.render(markdown,answer,turn.optString("answer"));card.addView(answer);
                if(!turn.optString("error").isEmpty())card.addView(text(turn.optString("error"),12,0xffbe4836));
            }
        }
        if(to<questions.length())body.addView(button("下一页",()->{page++;render();}));
    }
    private void continueQuestion(String questionId){setResult(RESULT_OK,new Intent().putExtra("examId",examId).putExtra("questionId",questionId));finish();}
    private Bitmap decode(String name,int max)throws Exception{
        File file=store.image(examId,name);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);if(options.outWidth<1)throw new IOException("图片不存在");options.inSampleSize=1;while(options.outWidth/options.inSampleSize>max||options.outHeight/options.inSampleSize>max)options.inSampleSize*=2;options.inJustDecodeBounds=false;return BitmapFactory.decodeFile(file.getPath(),options);
    }
    private void loadImage(ImageView view,String name,int max,int token){worker.execute(()->{try{Bitmap b=decode(name,max);runOnUiThread(()->{if(!closed&&generation==token)view.setImageBitmap(b);});}catch(Exception e){runOnUiThread(()->{if(!closed&&generation==token)view.setContentDescription("图片无法读取");});}});}
    private void showImage(String name){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);AlertDialog dialog=UiTheme.dialog(this).setTitle("题目图片").setView(image).setPositiveButton("关闭",null).create();dialog.show();image.setMinimumHeight(dp(350));loadImage(image,name,1600,generation);}
    private void exportText(){try{export=ExamStore.exportText(exam).getBytes(StandardCharsets.UTF_8);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/markdown").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"exam-"+examId+".md");startActivityForResult(intent,711);}catch(Exception e){Toast.makeText(this,"无法导出这份记录",Toast.LENGTH_LONG).show();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==711&&result==RESULT_OK&&data!=null&&export!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException();out.write(export);Toast.makeText(this,"笔试文字已导出",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"导出失败",Toast.LENGTH_LONG).show();}finally{export=null;}}}
    @Override protected void onDestroy(){closed=true;generation++;worker.shutdownNow();super.onDestroy();}
}
