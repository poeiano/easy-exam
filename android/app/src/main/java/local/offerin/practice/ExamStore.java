package local.offerin.practice;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** App-private, file-backed exam groups. Images stay on disk, outside preference XML. */
public final class ExamStore {
    public static final String DEFAULT_TITLE="默认记录";
    private final File root;
    public ExamStore(File root)throws IOException{this.root=root.getCanonicalFile();if(!this.root.isDirectory()&&!this.root.mkdirs())throw new IOException("无法创建笔试记录目录");}
    private static boolean validId(String id){return id!=null&&id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    private File directory(String id)throws IOException{if(!validId(id))throw new IOException("笔试记录编号无效");File dir=new File(root,id).getCanonicalFile();if(!root.equals(dir.getParentFile()))throw new IOException("笔试记录路径无效");return dir;}
    private static void atomic(File file,byte[] bytes)throws IOException{
        File temp=new File(file.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
        try{Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private void write(JSONObject exam)throws Exception{atomic(new File(directory(exam.getString("id")),"exam.json"),exam.toString().getBytes(StandardCharsets.UTF_8));}
    public synchronized JSONObject read(String id)throws Exception{return new JSONObject(new String(Files.readAllBytes(new File(directory(id),"exam.json").toPath()),StandardCharsets.UTF_8));}
    private JSONObject active(String id)throws Exception{JSONObject e=read(id);if(!"active".equals(e.optString("status")))throw new IOException("这份笔试已结束，请新建笔试记录");return e;}
    private static JSONObject find(JSONArray array,String id)throws Exception{for(int i=0;i<array.length();i++){JSONObject item=array.getJSONObject(i);if(id.equals(item.optString("id")))return item;}throw new IOException("题目或解答记录不存在");}
    public synchronized String create(String title)throws Exception{
        String id=UUID.randomUUID().toString();File dir=directory(id);if(!dir.mkdir())throw new IOException("无法创建笔试记录，请检查存储空间");
        long now=System.currentTimeMillis();JSONObject exam=new JSONObject().put("schema",1).put("id",id).put("title",title.trim().isEmpty()?DEFAULT_TITLE:title.trim()).put("createdAt",now).put("updatedAt",now).put("endedAt",0).put("status","active").put("questions",new JSONArray());write(exam);return id;
    }
    public synchronized String addQuestion(String examId,byte[] image,String source)throws Exception{
        JSONObject exam=active(examId);String id=UUID.randomUUID().toString(),name="";
        if(image!=null&&image.length>0){name=id+".jpg";atomic(new File(directory(examId),name),image);}
        JSONObject q=new JSONObject().put("id",id).put("createdAt",System.currentTimeMillis()).put("source",source).put("originalImage",name).put("image",name).put("turns",new JSONArray());exam.getJSONArray("questions").put(q);exam.put("updatedAt",System.currentTimeMillis());write(exam);return id;
    }
    public synchronized String crop(String examId,String questionId,byte[] bytes)throws Exception{
        JSONObject exam=active(examId),q=find(exam.getJSONArray("questions"),questionId);String name=UUID.randomUUID()+".jpg";
        atomic(new File(directory(examId),name),bytes);q.put("image",name);exam.put("updatedAt",System.currentTimeMillis());write(exam);return name;
    }
    public synchronized String beginTurn(String examId,String questionId,String prompt,String model)throws Exception{
        JSONObject exam=active(examId),q=find(exam.getJSONArray("questions"),questionId);String id=UUID.randomUUID().toString();
        q.getJSONArray("turns").put(new JSONObject().put("id",id).put("prompt",prompt).put("model",model).put("answer","").put("error","").put("status","generating").put("image",q.optString("image")).put("createdAt",System.currentTimeMillis()));exam.put("updatedAt",System.currentTimeMillis());write(exam);return id;
    }
    public synchronized boolean updateTurn(String examId,String questionId,String turnId,String answer,String status,String error)throws Exception{
        return updateTurn(examId,questionId,turnId,answer,status,error,0);
    }
    public synchronized boolean updateTurn(String examId,String questionId,String turnId,String answer,String status,String error,long revision)throws Exception{
        if(!Arrays.asList("generating","complete","failed","stopped").contains(status))throw new IOException("解答状态无效");
        JSONObject exam=read(examId),q=find(exam.getJSONArray("questions"),questionId),turn=find(q.getJSONArray("turns"),turnId);
        // A queued streaming update must never overwrite a completed/stopped answer.
        if(!"generating".equals(turn.optString("status")))return false;
        if(revision==0)revision=turn.optLong("revision")+1;
        if(revision<=turn.optLong("revision"))return false;
        turn.put("answer",answer).put("error",error).put("status",status).put("revision",revision).put("updatedAt",System.currentTimeMillis());exam.put("updatedAt",System.currentTimeMillis());write(exam);return true;
    }
    private static void interruptTurns(JSONObject exam,String status)throws Exception{
        JSONArray questions=exam.getJSONArray("questions");for(int i=0;i<questions.length();i++){JSONArray turns=questions.getJSONObject(i).getJSONArray("turns");for(int j=0;j<turns.length();j++){JSONObject turn=turns.getJSONObject(j);if("generating".equals(turn.optString("status")))turn.put("status",status);}}
    }
    public synchronized void finish(String id)throws Exception{JSONObject exam=read(id);interruptTurns(exam,"stopped");exam.put("status","finished").put("endedAt",System.currentTimeMillis());write(exam);}
    public synchronized JSONObject resume(String id)throws Exception{
        JSONObject exam=read(id);interruptTurns(exam,"interrupted");long now=System.currentTimeMillis();
        exam.put("status","active").put("endedAt",0).put("resumedAt",now).put("updatedAt",now);write(exam);return exam;
    }
    public static JSONObject selectQuestion(JSONObject exam,String id)throws Exception{
        JSONArray questions=exam.getJSONArray("questions");if(id!=null&&!id.isEmpty())return find(questions,id);
        return questions.length()==0?null:questions.getJSONObject(questions.length()-1);
    }
    public static JSONArray conversation(JSONObject question)throws Exception{
        JSONArray all=new JSONArray();if(question==null)return all;JSONArray turns=question.getJSONArray("turns");
        for(int i=0;i<turns.length();i++){JSONObject turn=turns.getJSONObject(i);if(!turn.optString("answer").trim().isEmpty()){
            all.put(new JSONObject().put("role","user").put("content",turn.optString("prompt")));
            all.put(new JSONObject().put("role","assistant").put("content",turn.getString("answer")));
        }}
        JSONArray recent=new JSONArray();for(int i=Math.max(0,all.length()-6);i<all.length();i++)recent.put(all.getJSONObject(i));return recent;
    }
    public synchronized void recoverInterrupted()throws Exception{for(JSONObject item:list()){if("active".equals(item.optString("status"))){JSONObject e=read(item.getString("id"));interruptTurns(e,"interrupted");e.put("status","interrupted");write(e);}}}
    public synchronized List<JSONObject> list()throws Exception{
        List<JSONObject> result=new ArrayList<>();File[] files=root.listFiles();if(files==null)throw new IOException("无法读取笔试记录");
        for(File file:files)if(file.isDirectory()&&validId(file.getName())){JSONObject e=read(file.getName());JSONArray questions=e.getJSONArray("questions");int turns=0;for(int i=0;i<questions.length();i++)turns+=questions.getJSONObject(i).getJSONArray("turns").length();
            result.add(new JSONObject().put("id",e.getString("id")).put("title",e.getString("title")).put("createdAt",e.getLong("createdAt")).put("status",e.optString("status")).put("legacyAt",e.optString("legacyAt")).put("questions",questions.length()).put("turns",turns));}
        result.sort((a,b)->Long.compare(b.optLong("createdAt"),a.optLong("createdAt")));return result;
    }
    public synchronized File image(String examId,String name)throws IOException{
        if(name==null||!name.matches("[a-f0-9-]{36}\\.jpg"))throw new IOException("图片文件无效");File dir=directory(examId),file=new File(dir,name).getCanonicalFile();if(!dir.equals(file.getParentFile()))throw new IOException("图片路径无效");return file;
    }
    public synchronized void delete(String examId)throws Exception{File dir=directory(examId);if(!dir.isDirectory())return;File[] files=dir.listFiles();if(files==null)throw new IOException("无法删除记录");for(File f:files){if(!dir.equals(f.getCanonicalFile().getParentFile())||f.isDirectory())throw new IOException("记录文件路径异常");if(!f.delete())throw new IOException("删除图片或解答失败");}if(!dir.delete())throw new IOException("删除笔试记录失败");}
    public synchronized void importLegacy(JSONArray old)throws Exception{
        File marker=new File(root,".legacy-imported");if(marker.exists())return;
        for(int i=0;i<old.length();i++){JSONObject entry=old.optJSONObject(i);if(entry==null)continue;String stable=UUID.nameUUIDFromBytes(("legacy:"+i+":"+entry.toString()).getBytes(StandardCharsets.UTF_8)).toString();File dir=directory(stable);if(new File(dir,"exam.json").exists())continue;if(!dir.exists()&&!dir.mkdir())throw new IOException("旧记录迁移失败");
            String qid=UUID.randomUUID().toString();JSONArray turns=new JSONArray().put(new JSONObject().put("id",UUID.randomUUID().toString()).put("prompt",entry.optString("question")).put("answer",entry.optString("answer")).put("status","complete").put("createdAt",0).put("image","").put("error",""));
            JSONObject question=new JSONObject().put("id",qid).put("source","旧版文字记录").put("createdAt",0).put("image","").put("originalImage","").put("turns",turns);
            JSONObject exam=new JSONObject().put("schema",1).put("id",stable).put("title","旧作答记录").put("legacyAt",entry.optString("at")).put("createdAt",System.currentTimeMillis()-(old.length()-i)*1000L).put("status","finished").put("questions",new JSONArray().put(question));write(exam);
        }
        atomic(marker,new byte[]{1});
    }
    public static String exportText(JSONObject exam)throws Exception{
        StringBuilder out=new StringBuilder("# "+exam.getString("title")+"\n\n");JSONArray qs=exam.getJSONArray("questions");for(int i=0;i<qs.length();i++){JSONObject q=qs.getJSONObject(i);out.append("## 第 ").append(i+1).append(" 题\n\n");JSONArray turns=q.getJSONArray("turns");if(turns.length()==0)out.append("已保存图片，尚未解答。\n\n");for(int j=0;j<turns.length();j++){JSONObject t=turns.getJSONObject(j);out.append("### ").append(j==0?"解答":"追问 "+j).append("\n\n").append(t.optString("prompt")).append("\n\n").append(t.optString("answer")).append("\n\n");if(!t.optString("error").isEmpty())out.append("状态：").append(t.optString("error")).append("\n\n");}}return out.toString();
    }
}
