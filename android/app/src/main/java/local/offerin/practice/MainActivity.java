package local.offerin.practice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.Window;
import android.widget.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.*;
import org.json.*;
import io.noties.markwon.Markwon;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public final class MainActivity extends Activity {
    private static final int INK=UiTheme.INK,MUTED=UiTheme.MUTED,BLUE=UiTheme.BLUE,BG=UiTheme.BG;
    private final Button[] navButtons=new Button[3];
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ModelClient modelClient = new ModelClient();
    private final BalanceClient balanceClient = new BalanceClient();
    private TextView balanceAmount,balanceMeta,balanceBadge;
    private Button balanceRefresh;
    private Call balanceCall;
    private long balanceLastAttempt,balanceUpdated;
    private int balanceGeneration;
    private String balanceSnapshotInfo="";
    private SharedPreferences prefs;
    private volatile WebSocket socket;
    private OkHttpClient linkClient;
    private volatile boolean paired, destroyed;
    private volatile int linkGeneration;
    private Markwon markdown;
    private long lastAnswerRender;
    private Pairing savedPairing;
    private boolean reconnectEnabled;
    private int reconnectAttempt;
    private Runnable reconnectTask;
    private int generation;
    private Call modelCall;
    private String pendingAnswer = "", currentImage = "", currentAnswer = "", currentPrompt = "", pairHost = "";
    private byte[] exportBytes;
    private Bitmap currentBitmap;
    private JSONArray conversation = new JSONArray();
    private ExamStore examStore;
    private volatile String activeExamId;
    private String activeExamTitle="",currentQuestionId="",activeTurnId="",historyError="";
    private long lastPartialSave,turnRevision;
    private int examQuestionCount,historyGeneration,historyLimit=20;
    private boolean historyDeleting,resumingExam;
    private final java.util.Set<String> pendingCaptures=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private TextView examHeading;
    private Button endExamButton;
    private final java.util.concurrent.ConcurrentHashMap<String,String> captureExams=new java.util.concurrent.ConcurrentHashMap<>();
    private LinearLayout root, pageWork, pageHistory, pageSettings, historyRows;
    private FrameLayout pages;
    private TextView status, answerView, imageInfo, taskStatus;
    private ImageView preview;
    private EditText question, baseField, keyField, modelField, customPrompt;
    private Spinner answerStyle;
    private CheckBox autoAnswer, streamAnswer, keepAwake;
    private Button askButton, stopButton;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("settings",0);
        if (!BuildConfig.DEFAULT_API_KEY.isEmpty() && !BuildConfig.DEFAULT_PROFILE_ID.equals(prefs.getString("defaultProfileImported",""))) {
            try {
                // User-requested provider migration: replace the previous profile once.
                SecretStore.save(this,BuildConfig.DEFAULT_API_KEY);
                prefs.edit().putString("base",BuildConfig.DEFAULT_BASE_URL).putString("model",BuildConfig.DEFAULT_MODEL)
                    .putBoolean("stream",true).putBoolean("defaultKeyImported",true)
                    .putString("defaultProfileImported",BuildConfig.DEFAULT_PROFILE_ID).apply();
            } catch (Exception ignored) { /* Retry on the next launch if the keystore is temporarily unavailable. */ }
        }
        markdown = AnswerRenderer.create(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        try{examStore=new ExamStore(new File(getFilesDir(),"exam-records"));examStore.importLegacy(new JSONArray(prefs.getString("sessions","[]")));examStore.recoverInterrupted();prefs.edit().remove("sessions").apply();}catch(Exception e){historyError="笔试记录无法加载，请检查存储空间；原记录未清空。";}
        buildUI();
        root.post(this::restoreConnection);
        root.post(()->refreshBalance(false));
        if (prefs.getBoolean("awake",true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private int dp(float value) { return (int)(value*getResources().getDisplayMetrics().density+.5f); }
    private TextView text(String value, float size, int color) { return UiTheme.text(this,value,size,color); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v; }
    private GradientDrawable bg(int color, int radius) { GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d; }
    private void space(LinearLayout v,int h) { View s=new View(this);v.addView(s,new LinearLayout.LayoutParams(1,dp(h))); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c=column();c.setPadding(dp(16),dp(17),dp(16),dp(17));c.setBackground(UiTheme.surface(this,Color.WHITE,18,true));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(c,lp);return c;
    }
    private Button button(String label, boolean primary, Runnable action) {
        return UiTheme.button(this,label,primary,action);
    }
    private void row(LinearLayout parent, View... views) {
        LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);
        for(int i=0;i<views.length;i++){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(8);r.addView(views[i],lp);}parent.addView(r);
    }
    private EditText field(LinearLayout parent,String label,String value,boolean multi) {
        parent.addView(text(label,12,MUTED));space(parent,5);
        EditText e=new EditText(this);e.setText(value);UiTheme.input(e);
        e.setSingleLine(!multi);if(multi){e.setMinLines(2);e.setMaxLines(5);e.setGravity(Gravity.TOP);}parent.addView(e,new LinearLayout.LayoutParams(-1,-2));space(parent,14);return e;
    }
    private LinearLayout page() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        FrameLayout holder=new FrameLayout(this);scroll.addView(holder,new ScrollView.LayoutParams(-1,-2));
        LinearLayout content=column();content.setPadding(dp(16),dp(20),dp(16),dp(24));holder.addView(content,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob)->{int width=Math.min(r-l,dp(760));if(width>0&&content.getLayoutParams().width!=width){content.getLayoutParams().width=width;content.requestLayout();}});
        pages.addView(scroll,new FrameLayout.LayoutParams(-1,-1));return content;
    }
    private void buildUI() {
        root=column();root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout header=column();header.setPadding(dp(20),dp(14),dp(20),dp(14));header.setBackgroundColor(Color.WHITE);
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("easy exam",22,INK);brand.addView(title,new LinearLayout.LayoutParams(0,-2,1));balanceBadge=text("模型与余额",11,BLUE);balanceBadge.setPadding(dp(10),dp(10),dp(10),dp(10));balanceBadge.setMinHeight(dp(44));balanceBadge.setGravity(Gravity.CENTER);balanceBadge.setMaxWidth(dp(145));balanceBadge.setSingleLine(true);balanceBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);balanceBadge.setBackground(UiTheme.interactiveSurface(this,0xffedf2fd,12));balanceBadge.setOnClickListener(v->showPage(2));brand.addView(balanceBadge);header.addView(brand);
        status=text("● 未连接电脑 · 点击扫码",12,MUTED);status.setPadding(dp(10),dp(10),dp(10),dp(10));status.setBackground(UiTheme.interactiveSurface(this,BG,12));status.setMinHeight(dp(44));status.setGravity(Gravity.CENTER_VERTICAL);status.setOnClickListener(v->pairDialog());LinearLayout.LayoutParams statusLayout=new LinearLayout.LayoutParams(-1,-2);statusLayout.topMargin=dp(12);header.addView(status,statusLayout);root.addView(header);
        pages=new FrameLayout(this);root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        pageWork=page();pageHistory=page();pageSettings=page();
        buildWork();buildHistory();buildSettings();updateExamHeading();
        LinearLayout nav=new LinearLayout(this);nav.setPadding(dp(12),dp(8),dp(12),dp(8));nav.setBackgroundColor(Color.WHITE);
        String[] labels={"笔试","记录","设置"};
        for(int i=0;i<labels.length;i++){final int n=i;Button b=button(labels[i],false,()->showPage(n));navButtons[i]=b;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(4),0,dp(4),0);nav.addView(b,lp);}root.addView(nav);showPage(0);
    }
    private void showPage(int n) { for(int i=0;i<pages.getChildCount();i++) pages.getChildAt(i).setVisibility(i==n?View.VISIBLE:View.GONE);for(int i=0;i<navButtons.length;i++)if(navButtons[i]!=null){navButtons[i].setTextColor(i==n?BLUE:MUTED);navButtons[i].setBackground(UiTheme.interactiveSurface(this,i==n?0xffeaf0ff:Color.WHITE,12));navButtons[i].setSelected(i==n);}if(n==1)renderHistory(); }
    private void buildWork() {
        TextView headline=text("笔试工作台",23,INK);pageWork.addView(headline);space(pageWork,6);pageWork.addView(text("选择记录，接收题目，继续你的解答。",12,MUTED));space(pageWork,18);
        LinearLayout examCard=card(pageWork);examHeading=text("请新建笔试，或在记录页继续已有笔试。",16,INK);examCard.addView(examHeading);
        space(examCard,12);endExamButton=button("结束保存",false,()->finishExam(true));row(examCard,button("新建笔试",true,this::newExamDialog),button("继续记录",false,()->showPage(1)),endExamButton);space(examCard,10);examCard.addView(text("题图、解答和追问自动保存在当前记录中。",11,MUTED));
        LinearLayout imageCard=card(pageWork);imageCard.addView(text("当前题目",16,INK));space(imageCard,10);
        preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackground(UiTheme.surface(this,BG,12,true));preview.setClipToOutline(true);imageCard.addView(preview,new LinearLayout.LayoutParams(-1,dp(210)));preview.setContentDescription("当前已接收的电脑截图");
        imageInfo=text("连接电脑后，截图会显示在这里。",11,MUTED);imageInfo.setPadding(0,dp(9),0,dp(12));imageCard.addView(imageInfo);
        LinearLayout qCard=card(pageWork);
        question=field(qCard,"补充题干或追问（可不填）","",true);question.setHint("例如：用 Java 解答，并解释时间复杂度");
        answerStyle=new Spinner(this);ArrayAdapter<String> styles=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"答案 + 关键解析","只给简明答案","详细推导","编程题：代码与复杂度"});answerStyle.setAdapter(styles);answerStyle.setMinimumHeight(dp(50));answerStyle.setPadding(dp(8),0,dp(34),0);answerStyle.setBackground(UiTheme.selectSurface(this));qCard.addView(answerStyle,new LinearLayout.LayoutParams(-1,-2));space(qCard,12);
        askButton=button("对当前题目作答",true,()->ask(false));stopButton=button("停止生成",false,this::cancelGeneration);row(qCard,askButton,stopButton);stopButton.setEnabled(false);
        space(qCard,10);taskStatus=text("本地配对无需账号；作答使用你配置的视觉模型。",11,MUTED);qCard.addView(taskStatus);
        LinearLayout answerCard=card(pageWork);row(answerCard,text("作答结果",16,INK),button("复制",false,()->copy(currentAnswer)),button("导出",false,this::exportAnswer));space(answerCard,14);
        answerView=text("答案将在这里逐步显示。\n\n首次使用，请先在设置中填写模型服务地址和支持图片的模型名称。",15,INK);answerView.setTextIsSelectable(true);answerView.setLineSpacing(dp(5),1);answerCard.addView(answerView);
    }
    private void buildHistory(){
        pageHistory.addView(text("笔试记录",22,INK));pageHistory.addView(text("按每场笔试保存全部题图、答案和追问；仅在本机保存，可逐场回看。",12,MUTED));space(pageHistory,12);
        row(pageHistory,button("新建笔试",true,this::newExamDialog),button("清空记录",false,()->UiTheme.dialog(this).setTitle("清空全部笔试记录？").setMessage("所有已保存的题图和答案将被删除，当前笔试也会结束。").setNegativeButton("取消",null).setPositiveButton("删除全部",(d,w)->clearExamHistory()).show()));space(pageHistory,16);historyRows=column();pageHistory.addView(historyRows);
    }
    private void renderHistory(){
        final int token=++historyGeneration;historyRows.removeAllViews();historyRows.addView(text("正在读取记录…",13,MUTED));
        if(examStore==null||!historyError.isEmpty()){historyRows.removeAllViews();historyRows.addView(text(historyError,14,MUTED));return;}
        worker.execute(()->{try{java.util.List<JSONObject> entries=examStore.list();runOnUiThread(()->{if(destroyed||token!=historyGeneration)return;historyRows.removeAllViews();
            if(entries.isEmpty())historyRows.addView(text("还没有笔试记录。请先新建笔试，再开始搜题。",14,MUTED));
            for(int i=0;i<Math.min(entries.size(),historyLimit);i++){JSONObject e=entries.get(i);String id=e.optString("id");LinearLayout c=card(historyRows);c.addView(text(e.optString("title"),17,INK));String when=e.optString("legacyAt");if(when.isEmpty())when=java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(e.optLong("createdAt")));
                c.addView(text(when+" · "+e.optInt("questions")+" 道题 · "+e.optInt("turns")+" 次解答／追问"+(id.equals(activeExamId)?" · 进行中":""),11,MUTED));
                row(c,button("继续作答",true,()->resumeExam(id,"")),button("回看记录",false,()->startActivityForResult(new Intent(this,ExamReviewActivity.class).putExtra("examId",id),712)),button("删除",false,()->deleteExam(id)));
            }
            if(entries.size()>historyLimit)historyRows.addView(button("加载更多记录",false,()->{historyLimit+=20;renderHistory();}));
        });}catch(Exception e){runOnUiThread(()->{if(!destroyed&&token==historyGeneration){historyRows.removeAllViews();historyRows.addView(text("记录读取失败，原文件已保留。",14,MUTED));}});}});
    }
    private void deleteExam(String id){if(resumingExam){message("正在恢复笔试，请稍后再删除");return;}if(id.equals(activeExamId)){message("请先结束这次笔试，再删除记录");return;}
        UiTheme.dialog(this).setTitle("删除这份笔试记录？").setMessage("同时删除本次保存的所有题图和答案。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->worker.execute(()->{try{examStore.delete(id);runOnUiThread(()->{if(!destroyed)renderHistory();});}catch(Exception e){runOnUiThread(()->message("删除失败，部分记录仍被保留"));}})).show();
    }
    private void clearExamHistory(){if(historyDeleting||!finishExam(false))return;historyDeleting=true;worker.execute(()->{try{for(JSONObject e:examStore.list())examStore.delete(e.getString("id"));runOnUiThread(()->{historyDeleting=false;if(!destroyed)renderHistory();});}catch(Exception e){runOnUiThread(()->{historyDeleting=false;message("未能清空全部记录，请检查存储空间");});}});}
    private boolean requireExam(){if(resumingExam){message("正在恢复笔试，请稍后");return false;}if(activeExamId!=null)return true;UiTheme.dialog(this).setTitle("选择笔试记录").setMessage("可以新建一次笔试，或回到已有记录继续作答。").setNegativeButton("取消",null).setNeutralButton("继续已有记录",(d,w)->showPage(1)).setPositiveButton("新建笔试",(d,w)->newExamDialog()).show();return false;}
    private void resumeExam(String id,String requestedQuestion){
        if(id==null||id.isEmpty()){message("笔试记录编号无效");return;}
        if(resumingExam||historyDeleting){message("正在处理笔试记录，请稍后");return;}
        if(examStore==null||!historyError.isEmpty()){message(historyError);return;}
        if(id.equals(activeExamId)&&(requestedQuestion.isEmpty()||requestedQuestion.equals(currentQuestionId))){showPage(0);return;}
        if(!finishExam(false))return;
        resumingExam=true;updateExamHeading();taskStatus.setText("正在恢复笔试及题图…");
        worker.execute(()->{try{
            JSONObject loaded=examStore.read(id),selected=ExamStore.selectQuestion(loaded,requestedQuestion);Bitmap restored=null;String encoded="";boolean missing=false;
            if(selected!=null&&!selected.optString("image").isEmpty()){
                try{byte[] bytes=java.nio.file.Files.readAllBytes(examStore.image(id,selected.getString("image")).toPath());
                    if(bytes.length>6*1024*1024)throw new Exception();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                    if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>4096||bounds.outHeight>8192)throw new Exception();restored=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(restored==null)throw new Exception();encoded=Base64.encodeToString(bytes,Base64.NO_WRAP);
                }catch(Exception e){missing=true;}
            }
            JSONArray restoredConversation=ExamStore.conversation(selected);JSONObject resumed=examStore.resume(id);
            final Bitmap bitmap=restored;final String image=encoded;final boolean missingImage=missing;
            runOnUiThread(()->{if(destroyed)return;try{
                resumingExam=false;activeExamId=id;activeExamTitle=resumed.optString("title");examQuestionCount=resumed.getJSONArray("questions").length();clearCurrentQuestion();
                if(selected!=null){currentQuestionId=selected.getString("id");currentImage=image;currentBitmap=bitmap;conversation=restoredConversation;preview.setImageBitmap(bitmap);
                    JSONArray turns=selected.getJSONArray("turns");if(turns.length()>0){JSONObject last=turns.getJSONObject(turns.length()-1);currentPrompt=last.optString("prompt");currentAnswer=last.optString("answer");if(!currentAnswer.isEmpty())AnswerRenderer.render(markdown,answerView,currentAnswer);}
                    if(currentAnswer.isEmpty())answerView.setText("题目已恢复，可以继续解答或输入追问。");
                    imageInfo.setText(missingImage?"原题图暂时无法读取，可补充文字或重新截图":bitmap==null?"已恢复文字题及历史对话":"已恢复题图 · "+bitmap.getWidth()+" × "+bitmap.getHeight());
                }
                updateExamHeading();showPage(0);taskStatus.setText("已继续原笔试；可追问当前题目，或截图追加新题。旧答案保留。");
            }catch(Exception e){activeExamId=null;clearCurrentQuestion();updateExamHeading();message("笔试恢复失败，原记录已保留");}});
        }catch(Exception e){runOnUiThread(()->{resumingExam=false;if(!destroyed){updateExamHeading();message("笔试恢复失败，原记录已保留，请重试");}});}});
    }
    private void newExamDialog(){
        if(historyDeleting||resumingExam){message("正在处理旧记录，请稍后再新建");return;}
        if(examStore==null||!historyError.isEmpty()){message(historyError);return;}
        android.app.Dialog dialog=new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        LinearLayout content=column();content.setPadding(dp(24),dp(24),dp(24),dp(24));scroll.addView(content);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon=text("＋",25,BLUE);icon.setGravity(Gravity.CENTER);icon.setBackground(UiTheme.surface(this,0xffedf2fd,14,false));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(46)));
        TextView titleView=text("新建笔试记录",21,INK);LinearLayout.LayoutParams titleLayout=new LinearLayout.LayoutParams(0,-2,1);titleLayout.leftMargin=dp(14);heading.addView(titleView,titleLayout);content.addView(heading);
        space(content,16);content.addView(text("给这次练习起个名字，方便之后继续和回看。",13,MUTED));space(content,24);
        content.addView(text("记录名称",12,MUTED));space(content,8);
        EditText input=new EditText(this);input.setText(ExamStore.DEFAULT_TITLE);
        input.setHint("例如：秋招笔试 · 算法练习");input.setTextSize(15);input.setTextColor(INK);input.setHintTextColor(0xff98a5b8);
        input.setSingleLine(true);input.setSelectAllOnFocus(true);input.setMinHeight(dp(52));input.setPadding(dp(14),dp(12),dp(14),dp(12));
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
        UiTheme.input(input);input.setTextSize(15);
        content.addView(input,new LinearLayout.LayoutParams(-1,-2));space(content,10);
        content.addView(text("题图、解答和追问会自动保存在这份记录中。",11,MUTED));
        if(activeExamId!=null){space(content,16);TextView note=text("当前笔试会先结束并保存，再开始新的记录。",12,BLUE);note.setPadding(dp(12),dp(12),dp(12),dp(12));note.setBackground(UiTheme.surface(this,0xffedf2fd,10,false));content.addView(note);}
        TextView error=text("",12,0xffbe4836);error.setVisibility(View.GONE);error.setPadding(0,dp(12),0,0);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(error);
        space(content,24);Button cancel=button("取消",false,dialog::dismiss);Button start=button("开始笔试",true,()->{});row(content,cancel,start);
        Runnable create=()->{
            if(!start.isEnabled())return;
            if(historyDeleting||resumingExam){error.setText("正在处理记录，请稍后再试。");error.setVisibility(View.VISIBLE);return;}
            start.setEnabled(false);error.setVisibility(View.GONE);
            if(!finishExam(false)){start.setEnabled(true);return;}
            try{String title=input.getText().toString().trim();if(title.isEmpty())title=ExamStore.DEFAULT_TITLE;activeExamId=examStore.create(title);activeExamTitle=title;examQuestionCount=0;clearCurrentQuestion();updateExamHeading();showPage(0);taskStatus.setText("笔试已开始，可以截图搜题。题图和解答会自动保存。");dialog.dismiss();}
            catch(Exception e){start.setEnabled(true);error.setText("暂时无法创建，请检查手机存储空间后重试。");error.setVisibility(View.VISIBLE);}
        };
        start.setOnClickListener(v->create.run());input.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){create.run();return true;}return false;});
        content.setFocusableInTouchMode(true);content.requestFocus();dialog.setContentView(scroll);dialog.setCanceledOnTouchOutside(true);
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(UiTheme.surface(this,Color.WHITE,24,false));window.getDecorView().setClipToOutline(true);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.32f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}
        dialog.show();if(window!=null)window.setLayout(Math.min(dp(420),Math.max(dp(240),getResources().getDisplayMetrics().widthPixels-dp(48))),WindowManager.LayoutParams.WRAP_CONTENT);
    }
    private void updateExamHeading(){boolean active=activeExamId!=null&&!resumingExam;examHeading.setText(resumingExam?"正在恢复笔试…":active?activeExamTitle+" · 已保存 "+examQuestionCount+" 道题":"请新建笔试，或在记录页继续已有笔试。");endExamButton.setEnabled(active);askButton.setEnabled(active);}
    private void clearCurrentQuestion(){currentQuestionId="";currentImage="";currentAnswer="";currentPrompt="";currentBitmap=null;conversation=new JSONArray();pendingAnswer="";question.setText("");preview.setImageDrawable(null);imageInfo.setText("本次笔试的下一张截图会显示在这里。");answerView.setText("新建笔试后开始搜题，答案和题图将自动归档。");}
    private boolean finishExam(boolean report){if(resumingExam){message("正在恢复笔试，请稍后");return false;}if(activeExamId==null)return true;for(String id:pendingCaptures)if(activeExamId.equals(captureExams.get(id))){message("截图仍在接收，请收到后再结束或切换笔试");return false;}cancelGeneration();try{examStore.finish(activeExamId);activeExamId=null;activeExamTitle="";examQuestionCount=0;clearCurrentQuestion();updateExamHeading();if(report){taskStatus.setText("本次笔试已保存，可在记录页回看或继续作答。");showPage(1);}return true;}catch(Exception e){message("笔试结束保存失败，请检查存储空间后重试");return false;}}
    private boolean saveTurn(String state,String error){if(activeTurnId.isEmpty()||activeExamId==null)return true;try{examStore.updateTurn(activeExamId,currentQuestionId,activeTurnId,currentAnswer,state,error,++turnRevision);if(!state.equals("generating"))activeTurnId="";return true;}catch(Exception e){message("解答保存失败，请检查存储空间；当前答案仍可复制或导出");return false;}}
    private void savePartial(){if(activeTurnId.isEmpty()||System.currentTimeMillis()-lastPartialSave<1000)return;lastPartialSave=System.currentTimeMillis();final String exam=activeExamId,q=currentQuestionId,turn=activeTurnId,answer=currentAnswer;final long revision=++turnRevision;worker.execute(()->{try{examStore.updateTurn(exam,q,turn,answer,"generating","",revision);}catch(Exception e){runOnUiThread(()->message("部分解答保存失败，请检查存储空间"));}});}
    private void buildSettings() {
        pageSettings.addView(text("设置",23,INK));space(pageSettings,6);pageSettings.addView(text("连接、模型与使用偏好。",12,MUTED));space(pageSettings,18);
        LinearLayout connection=card(pageSettings);connection.addView(text("双端连接",18,INK));space(connection,10);connection.addView(text("启动电脑连接服务，然后扫描电脑二维码。也可以粘贴电脑生成的配对链接。",12,MUTED));space(connection,12);row(connection,button("扫描电脑二维码",true,this::scanComputer),button("断开并忘记电脑",false,this::forgetConnection));
        LinearLayout balanceCard=card(pageSettings);balanceRefresh=button("刷新",false,()->refreshBalance(true));row(balanceCard,text("平台可用余额",16,INK),balanceRefresh);space(balanceCard,12);
        balanceAmount=text("—",30,BLUE);balanceCard.addView(balanceAmount);space(balanceCard,8);balanceMeta=text("读取平台账户余额，不按本机调用估算。",12,MUTED);balanceCard.addView(balanceMeta);
        LinearLayout ai=card(pageSettings);ai.addView(text("视觉模型服务",18,INK));space(ai,12);
        baseField=field(ai,"API 服务地址",prefs.getString("base",""),false);baseField.setHint("https://你的服务/v1 或服务商 API 根地址");baseField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        modelField=field(ai,"支持图片的模型名称",prefs.getString("model",""),false);modelField.setHint("填写服务商实际提供的视觉模型 ID");
        keyField=field(ai,"API Key · 加密保存在本机","",false);keyField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        try{keyField.setText(SecretStore.read(this));}catch(Exception e){message("原密钥无法读取，请重新填写并保存");}
        customPrompt=field(ai,"自定义作答要求",prefs.getString("prompt",""),true);
        autoAnswer=new CheckBox(this);autoAnswer.setText("电脑快捷键截图后自动作答");autoAnswer.setChecked(prefs.getBoolean("auto",true));ai.addView(autoAnswer);
        streamAnswer=new CheckBox(this);streamAnswer.setText("流式输出（逐字显示）");streamAnswer.setChecked(prefs.getBoolean("stream",true));ai.addView(streamAnswer);
        keepAwake=new CheckBox(this);keepAwake.setText("使用时保持屏幕常亮");keepAwake.setChecked(prefs.getBoolean("awake",true));ai.addView(keepAwake);for(CheckBox toggle:new CheckBox[]{autoAnswer,streamAnswer,keepAwake}){toggle.setMinHeight(dp(48));toggle.setTextSize(13);toggle.setTextColor(INK);toggle.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));}space(ai,12);
        ai.addView(button("保存配置",true,this::saveSettings));space(ai,12);ai.addView(text("密钥由 Android Keystore 加密保存在主端。仅在你触发作答时，题目图片与文字才会发送到上述模型服务。",11,MUTED));
        LinearLayout about=card(pageSettings);about.addView(text("easy exam "+BuildConfig.VERSION_NAME,16,INK));space(about,8);about.addView(text("手机管理题目与解答，电脑负责截图。\n普通截图快捷键：Alt + S\n双击 Ctrl 开始长截屏，手动向下滚动，再次双击完成。\n电脑收起到托盘后仍可截图。",12,MUTED));
    }
    private boolean saveSettings() {
        try {
            if(!baseField.getText().toString().trim().isEmpty())ModelClient.endpoint(baseField.getText().toString());
            SecretStore.save(this,keyField.getText().toString().trim());
            prefs.edit().putString("base",baseField.getText().toString().trim()).putString("model",modelField.getText().toString().trim()).putString("prompt",customPrompt.getText().toString()).putBoolean("auto",autoAnswer.isChecked()).putBoolean("stream",streamAnswer.isChecked()).putBoolean("awake",keepAwake.isChecked()).apply();
            if(keepAwake.isChecked())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            resetBalance();refreshBalance(true);message("配置已保存");return true;
        }catch(Exception e){message("保存失败："+e.getMessage());return false;}
    }
    private void resetBalance(){balanceGeneration++;if(balanceCall!=null)balanceCall.cancel();balanceCall=null;balanceUpdated=0;balanceLastAttempt=0;balanceSnapshotInfo="";balanceAmount.setText("—");balanceAmount.setTextColor(BLUE);balanceBadge.setText("模型与余额");balanceBadge.setTextColor(BLUE);}
    private void refreshBalance(boolean force){
        if(destroyed||balanceAmount==null||balanceCall!=null)return;
        if(!force&&System.currentTimeMillis()-balanceLastAttempt<15000)return;
        balanceLastAttempt=System.currentTimeMillis();final int token=++balanceGeneration;
        balanceRefresh.setEnabled(false);balanceMeta.setText((balanceSnapshotInfo.isEmpty()?"":balanceSnapshotInfo+"\n")+"正在查询平台余额…");
        try{
            balanceCall=balanceClient.query(prefs.getString("base",""),SecretStore.read(this),new BalanceClient.Listener(){
                public void done(BalanceClient.Balance value){runOnUiThread(()->{if(destroyed||token!=balanceGeneration)return;balanceCall=null;balanceRefresh.setEnabled(true);balanceUpdated=System.currentTimeMillis();
                    balanceAmount.setText(value.symbol()+" "+value.availableText());balanceAmount.setTextColor(value.available.signum()<=0?Color.rgb(190,72,54):BLUE);
                    balanceBadge.setText(value.symbol()+" "+value.availableText());balanceBadge.setTextColor(value.available.signum()<=0?Color.rgb(190,72,54):BLUE);balanceBadge.setContentDescription("平台可用余额 "+value.availableText()+" "+value.currency+"，点击查看模型与余额设置");
                    balanceSnapshotInfo="账户余额 "+value.symbol()+" "+value.totalText()+" · 更新于 "+java.text.DateFormat.getTimeInstance().format(new java.util.Date(balanceUpdated));
                    balanceMeta.setText(balanceSnapshotInfo+"\n平台账户余额，包含其他应用的消费；非套餐次数。");});}
                public void error(String error){runOnUiThread(()->{if(destroyed||token!=balanceGeneration)return;balanceCall=null;balanceRefresh.setEnabled(true);balanceMeta.setText((balanceSnapshotInfo.isEmpty()?"":balanceSnapshotInfo+"\n")+error);});}
            });
        }catch(Exception e){balanceCall=null;balanceRefresh.setEnabled(true);balanceMeta.setText("余额查询无法启动，请检查已保存的服务配置与密钥");}
    }
    @Override protected void onResume(){super.onResume();if(prefs!=null&&System.currentTimeMillis()-balanceUpdated>=300000)refreshBalance(false);}
    private void scanComputer(){
        if(!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)){message("此设备没有可用摄像头，请使用粘贴连接链接");return;}
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.CAMERA},903);return;
        }
        try{new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("扫描电脑端的连接二维码").setBeepEnabled(false).setOrientationLocked(false).initiateScan();}
        catch(RuntimeException|LinkageError e){message("扫码页面无法启动，请使用粘贴连接链接（"+e.getClass().getSimpleName()+"）");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==903){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)scanComputer();else message("相机权限未允许，可在系统设置中开启，或使用粘贴连接链接");}
    }
    private void openDirect(String value){try{Pairing p=Pairing.direct(value);forgetConnection();startConnectionService();status.setText("● 正在直连电脑 "+p.host);connect(p);}catch(Exception e){message("连接链接无效，请重新复制电脑端配对链接");}}
    private void pairDialog(){
        UiTheme.dialog(this).setTitle("连接电脑副端").setItems(new String[]{"扫描电脑二维码","粘贴连接链接"},(d,which)->{
            if(which==0){scanComputer();return;}
            EditText input=new EditText(this);UiTheme.input(input);input.setHint("粘贴电脑端复制的完整配对链接");input.setSingleLine(false);
            UiTheme.dialog(this).setTitle("粘贴连接链接").setView(input).setNegativeButton("取消",null).setPositiveButton("连接",(dialog,w)->openDirect(input.getText().toString())).show();
        }).show();
    }
    private void startConnectionService(){
        try{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},902);startForegroundService(new Intent(this,ConnectionService.class));}catch(Exception e){taskStatus.setText("后台连接通知无法启动，请保持应用在前台使用。");}
    }
    private void restoreConnection(){try{String value=SecretStore.readSlot(this,"connection");if(!value.isEmpty()){savedPairing=Pairing.direct(value);startConnectionService();connect(savedPairing);}}catch(Exception e){SecretStore.clearConnection(this);message("保存的电脑连接无法读取，请重新扫码");}}
    private void forgetConnection(){disconnect();savedPairing=null;SecretStore.clearConnection(this);stopService(new Intent(this,ConnectionService.class));}
    private void scheduleReconnect(int token){
        if(destroyed||!reconnectEnabled||savedPairing==null||token!=linkGeneration||reconnectTask!=null)return;
        long delay=Math.min(30000L,3000L*(1L<<Math.min(reconnectAttempt++,4)));
        status.setText("● 连接暂断，"+(delay/1000)+" 秒后自动重连");
        reconnectTask=()->{reconnectTask=null;if(!destroyed&&reconnectEnabled&&savedPairing!=null&&token==linkGeneration)connect(savedPairing);};root.postDelayed(reconnectTask,delay);
    }
    private void connect(Pairing pairing) {
        reconnectEnabled=true;if(reconnectTask!=null){root.removeCallbacks(reconnectTask);reconnectTask=null;}
        final int linkToken=++linkGeneration;status.setText(pairing.isTrusted()?"● 正在自动连接已配对电脑…":"● 正在连接电脑…");pairHost=pairing.host;
        if(socket!=null){socket.close(1000,"New pairing attempt");socket=null;}
        if(linkClient!=null){linkClient.dispatcher().cancelAll();linkClient.connectionPool().evictAll();linkClient.dispatcher().executorService().shutdown();}
        worker.execute(()->{
            try {
                OkHttpClient client=pairing.client();
                if(destroyed||linkToken!=linkGeneration){client.dispatcher().executorService().shutdown();return;}
                linkClient=client;
                client.newWebSocket(new Request.Builder().url(pairing.wsUrl()).header("Origin","offerin-local-native").build(),new WebSocketListener(){
                    private boolean verified=pairing.isDirect(),reported;
                    private void failed(){if(reported||linkToken!=linkGeneration||destroyed)return;reported=true;boolean wasPaired=paired;paired=false;runOnUiThread(()->{status.setText("● 连接断开，点击重新扫码连接");status.setTextColor(MUTED);scheduleReconnect(linkToken);});}
                    @Override public void onOpen(WebSocket ws,Response response){if(linkToken!=linkGeneration||destroyed){ws.close(1000,"Superseded");return;}socket=ws;try{ws.send(pairing.hello(Build.MANUFACTURER+" "+Build.MODEL).toString());}catch(Exception e){failed();}}
                    @Override public void onMessage(WebSocket ws,String data){
                        if(ws!=socket||destroyed||linkToken!=linkGeneration)return;
                        try{JSONObject m=new JSONObject(data);String type=m.optString("type");
                            if(type.equals("pair-proof")){JSONObject confirm=pairing.confirm(m);verified=true;ws.send(confirm.toString());}
                            else if(type.equals("paired")){if(!verified)throw new Exception("Password not verified");if(m.has("deviceToken")){String link=pairing.trustedLink(m.getString("deviceToken"));SecretStore.saveSlot(MainActivity.this,"connection",link);savedPairing=Pairing.direct(link);}
                                paired=true;runOnUiThread(()->{reconnectAttempt=0;status.setText("● 已连接电脑 "+pairHost);status.setTextColor(Color.rgb(28,156,116));taskStatus.setText("已保存电脑配对；断网后自动重连，可直接截图。");});}
                            else if(type.equals("error")){if(!paired){failed();runOnUiThread(()->message(m.optString("message")));}runOnUiThread(()->{taskStatus.setText(m.optString("message"));});}
                            else if(type.equals("screenshot")&&paired){String target=captureExams.get(m.optString("requestId"));if(target==null)target=activeExamId;final String exam=target;if(exam==null)runOnUiThread(()->message("请先新建笔试记录，再使用电脑截图快捷键"));else{captureExams.put(m.optString("requestId"),exam);pendingCaptures.add(m.optString("requestId"));worker.execute(()->receiveScreenshot(m,exam));}}
                        }catch(Exception e){ws.close(1008,"Authentication rejected");failed();}
                    }
                    @Override public void onFailure(WebSocket ws,Throwable t,Response response){failed();if(linkToken==linkGeneration&&!destroyed)runOnUiThread(()->taskStatus.setText("连接电脑 "+pairing.host+":"+pairing.port+" 失败（"+t.getClass().getSimpleName()+"），请检查电脑服务及网络互通。"));}
                    @Override public void onClosed(WebSocket ws,int code,String reason){if((code==4003||code==4001)&&linkToken==linkGeneration){runOnUiThread(()->{if(linkToken!=linkGeneration)return;forgetConnection();status.setText(code==4003?"● 电脑已取消信任，请重新扫码":"● 此设备已在另一个窗口连接");});}else failed();}
                    @Override public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);}
                });
            }catch(Exception e){runOnUiThread(()->{if(linkToken==linkGeneration){scheduleReconnect(linkToken);taskStatus.setText("连接失败，请确认电脑服务已启动，网络互通后将自动重试。");}});}
        });
    }
    private void disconnect(){reconnectEnabled=false;if(reconnectTask!=null){root.removeCallbacks(reconnectTask);reconnectTask=null;}linkGeneration++;paired=false;if(socket!=null){socket.close(1000,"User disconnected");socket=null;}if(linkClient!=null){linkClient.dispatcher().cancelAll();linkClient.connectionPool().evictAll();linkClient.dispatcher().executorService().shutdown();linkClient=null;}if(status!=null){status.setText("● 未连接电脑副端　点击扫码连接");status.setTextColor(MUTED);}}
    private void requestCapture(boolean answer){
        if(!requireExam())return;
        if(!paired||socket==null){pairDialog();return;}String id=UUID.randomUUID().toString();captureExams.put(id,activeExamId);pendingCaptures.add(id);if(answer)pendingAnswer=id;
        try{if(!socket.send(new JSONObject().put("type","capture").put("requestId",id).put("answer",answer).toString()))throw new Exception();taskStatus.setText("正在接收电脑截图…");
            root.postDelayed(()->{pendingCaptures.remove(id);},10000);
        }catch(Exception e){pendingCaptures.remove(id);captureExams.remove(id);message("发送失败，请重新配对");}
    }
    private void receiveScreenshot(JSONObject m,String exam){
        try{
            if(!exam.equals(activeExamId)||examStore==null){pendingCaptures.remove(m.optString("requestId"));captureExams.remove(m.optString("requestId"));return;}
            String image=m.getString("image");if(image.length()>8*1024*1024)throw new Exception();
            byte[] bytes=Base64.decode(image,Base64.DEFAULT);Bitmap b=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(b==null||b.getWidth()>4096||b.getHeight()>8192)throw new Exception();
            final String questionId=examStore.addQuestion(exam,bytes,"电脑截图");
            runOnUiThread(()->{pendingCaptures.remove(m.optString("requestId"));captureExams.remove(m.optString("requestId"));if(destroyed||!exam.equals(activeExamId))return;cancelGeneration();currentQuestionId=questionId;examQuestionCount++;question.setText("");updateExamHeading();currentAnswer="";currentPrompt="";answerView.setText("新截图已接收，可以对当前题目作答。");currentBitmap=b;currentImage=image;conversation=new JSONArray();preview.setImageBitmap(b);imageInfo.setText("已收到图片 · "+b.getWidth()+" × "+b.getHeight()+" · "+(bytes.length/1024)+" KB");taskStatus.setText("截图已接收");showPage(0);
                boolean requested=m.optString("requestId").equals(pendingAnswer);if(requested)pendingAnswer="";
                if(requested||(m.optBoolean("answer")&&prefs.getBoolean("auto",true)))ask(true);
            });
        }catch(Exception e){pendingCaptures.remove(m.optString("requestId"));captureExams.remove(m.optString("requestId"));runOnUiThread(()->{message("截图读取或保存失败，请检查手机存储空间及图像大小");});}
    }
    private void ask(boolean fresh){
        if(!requireExam())return;
        String base=prefs.getString("base",""),model=prefs.getString("model","");
        if(base.trim().isEmpty()||model.trim().isEmpty()){taskStatus.setText("截图已接收；请先设置视觉模型服务。");showPage(2);message("请配置视觉模型服务后保存");return;}
        String text=question.getText().toString().trim();if(currentImage.isEmpty()&&text.isEmpty()){message("请先截取题目，或输入文字题干");return;}
        cancelGeneration();final int token=++generation;
        String[] style={"请给出答案和关键解析。","请优先给出简明答案，避免冗长展开。","请逐步详细推导并检查答案。","请给出算法思路、完整可运行代码、边界情况和时间空间复杂度。"};
        String prompt=(text.isEmpty()?"请识别并解答截图中的全部题目；有多道题时按题号逐题回答。":text)+"\n"+style[answerStyle.getSelectedItemPosition()]+"\n"+prefs.getString("prompt","");
        currentPrompt=text.isEmpty()?"解答截图中的题目":text;currentAnswer="";lastAnswerRender=0;answerView.setText("正在识别题目并生成答案…");taskStatus.setText("作答中…");stopButton.setEnabled(true);
        try{
            if(currentQuestionId.isEmpty()){currentQuestionId=examStore.addQuestion(activeExamId,null,"文字题目");examQuestionCount++;updateExamHeading();}
            activeTurnId=examStore.beginTurn(activeExamId,currentQuestionId,currentPrompt,model);lastPartialSave=0;turnRevision=0;
            String key=SecretStore.read(this);
            modelCall=modelClient.ask(base,key,model,prompt,currentImage,prefs.getBoolean("stream",true),fresh?new JSONArray():conversation,new ModelClient.Listener(){
                public void delta(String all){runOnUiThread(()->{if(token==generation&&!destroyed){currentAnswer=all;long now=android.os.SystemClock.uptimeMillis();if(now-lastAnswerRender>=150){AnswerRenderer.render(markdown,answerView,all);lastAnswerRender=now;}savePartial();}});}
                public void done(String answer){runOnUiThread(()->{if(token!=generation||destroyed)return;currentAnswer=answer;AnswerRenderer.render(markdown,answerView,answer);boolean saved=saveTurn("complete","");taskStatus.setText(saved?"作答完成 · 已保存到当前笔试记录":"作答完成，但记录保存失败，请复制或导出答案");modelCall=null;stopButton.setEnabled(false);refreshBalance(false);try{conversation.put(new JSONObject().put("role","user").put("content",currentPrompt));conversation.put(new JSONObject().put("role","assistant").put("content",answer));}catch(Exception ignored){}});}
                public void error(String error){runOnUiThread(()->{if(token!=generation||destroyed)return;boolean saved=saveTurn("failed",error);taskStatus.setText(error+(saved?" · 已记录本次作答状态":" · 记录保存失败，请检查存储空间"));if(currentAnswer.isEmpty())answerView.setText(error);else AnswerRenderer.render(markdown,answerView,currentAnswer);modelCall=null;stopButton.setEnabled(false);refreshBalance(false);});}
            });
        }catch(Exception e){saveTurn("failed","无法开始作答");taskStatus.setText("无法开始作答，请检查配置及手机存储空间");stopButton.setEnabled(false);}
    }
    private void cancelGeneration(){if(answerView!=null&&!currentAnswer.isEmpty())AnswerRenderer.render(markdown,answerView,currentAnswer);saveTurn("stopped","");generation++;if(modelCall!=null){modelCall.cancel();modelCall=null;}if(stopButton!=null)stopButton.setEnabled(false);if(taskStatus!=null)taskStatus.setText("已停止生成");}
    private void cropDialog(){
        if(currentBitmap==null){message("请先接收一张截图");return;}
        CropView crop=new CropView(this,currentBitmap);int h=Math.max(dp(260),getResources().getDisplayMetrics().heightPixels-dp(240));
        AlertDialog dialog=UiTheme.dialog(this).setTitle("拖动框选题目区域").setView(crop).setNegativeButton("取消",null).setPositiveButton("使用选区",null).create();dialog.show();crop.setLayoutParams(new FrameLayout.LayoutParams(-1,h));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{Bitmap b=crop.crop();ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,95,out);if(activeExamId==null||currentQuestionId.isEmpty())throw new Exception("请先新建笔试并截图");cancelGeneration();examStore.crop(activeExamId,currentQuestionId,out.toByteArray());currentAnswer="";answerView.setText("裁剪图已保存，可以对当前题目作答。");currentBitmap=b;currentImage=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);preview.setImageBitmap(b);imageInfo.setText("已裁剪 · "+b.getWidth()+" × "+b.getHeight());conversation=new JSONArray();dialog.dismiss();}catch(Exception e){message(e.getMessage());}});
    }
    private void saveImage(){if(currentImage.isEmpty()){message("还没有截图");return;}exportBytes=Base64.decode(currentImage,Base64.DEFAULT);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/jpeg").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"question-"+System.currentTimeMillis()+".jpg");startActivityForResult(i,710);}
    private void exportAnswer(){if(currentAnswer.isEmpty()){message("还没有可导出的答案");return;}exportBytes=("# 笔试练习记录\n\n## 问题\n"+currentPrompt+"\n\n## 作答\n"+currentAnswer).getBytes(StandardCharsets.UTF_8);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/markdown").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"answer-"+System.currentTimeMillis()+".md");startActivityForResult(i,710);}
    private void copy(String value){if(value.isEmpty()){message("没有可复制内容");return;}((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("easy exam",value));message("已复制");}
    private void message(String value){if(!destroyed)Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==712){if(result==RESULT_OK&&data!=null)resumeExam(data.getStringExtra("examId"),data.getStringExtra("questionId")==null?"":data.getStringExtra("questionId"));return;}IntentResult scan=IntentIntegrator.parseActivityResult(request,result,data);if(scan!=null){if(scan.getContents()!=null)openDirect(scan.getContents());return;}if(request==710&&result==RESULT_OK&&data!=null&&exportBytes!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new Exception();out.write(exportBytes);message("已保存");}catch(Exception e){message("保存失败");}finally{exportBytes=null;}}}
    @Override protected void onPause(){saveTurn("generating","");super.onPause();}
    @Override protected void onDestroy(){destroyed=true;balanceGeneration++;if(balanceCall!=null)balanceCall.cancel();cancelGeneration();disconnect();stopService(new Intent(this,ConnectionService.class));worker.shutdownNow();super.onDestroy();}
}

