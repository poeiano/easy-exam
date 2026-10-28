package local.offerin.practice;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import org.json.*;

public class ExamStoreTest {
    private File root;private ExamStore store;
    @Before public void create()throws Exception{root=Files.createTempDirectory("offerin-record-test-").toFile().getCanonicalFile();store=new ExamStore(root);}
    @After public void cleanup()throws Exception{for(JSONObject e:store.list())store.delete(e.getString("id"));File[] files=root.listFiles();if(files!=null)for(File f:files){if(!root.equals(f.getCanonicalFile().getParentFile())||f.isDirectory())throw new IOException("Unsafe cleanup");Files.deleteIfExists(f.toPath());}if(!root.getName().startsWith("offerin-record-test-"))throw new IOException("Unsafe test root");Files.delete(root.toPath());}
    private JSONObject question(String exam,String q)throws Exception{JSONArray all=store.read(exam).getJSONArray("questions");for(int i=0;i<all.length();i++)if(q.equals(all.getJSONObject(i).getString("id")))return all.getJSONObject(i);throw new AssertionError();}
    @Test public void duplicateDefaultNamesRemainIndependentRecords()throws Exception{
        String first=store.create("默认记录"),second=store.create("   ");
        assertNotEquals(first,second);assertEquals("默认记录",store.read(first).getString("title"));assertEquals("默认记录",store.read(second).getString("title"));
        store.addQuestion(first,null,"第一份题目");store.addQuestion(second,null,"第二份题目");
        store=new ExamStore(root);assertEquals(2,store.list().size());
        assertEquals("第一份题目",store.read(first).getJSONArray("questions").getJSONObject(0).getString("source"));
        store.delete(first);assertEquals(1,store.list().size());assertEquals("第二份题目",store.read(second).getJSONArray("questions").getJSONObject(0).getString("source"));
    }
    @Test public void examGroupsAllImagesAnswersAndFollowupsAcrossReload()throws Exception{
        String exam=store.create("模拟笔试"),q1=store.addQuestion(exam,new byte[]{1,2,3},"电脑截图"),q2=store.addQuestion(exam,new byte[]{4,5},"电脑截图");
        String first=store.beginTurn(exam,q1,"解答第一题","mock-model");store.updateTurn(exam,q1,first,"答案一","complete","");
        String follow=store.beginTurn(exam,q1,"为什么选 A","mock-model");store.updateTurn(exam,q1,follow,"解释一","complete","");
        String next=store.beginTurn(exam,q2,"第二题","mock-model");store.updateTurn(exam,q2,next,"答案二","complete","");store.finish(exam);
        store=new ExamStore(root);JSONObject saved=store.read(exam);assertEquals(2,saved.getJSONArray("questions").length());assertEquals(2,question(exam,q1).getJSONArray("turns").length());
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(store.image(exam,question(exam,q1).getString("originalImage")).toPath()));
        String export=ExamStore.exportText(saved);assertTrue(export.contains("答案一"));assertTrue(export.contains("解释一"));assertTrue(export.contains("答案二"));assertEquals(3,store.list().get(0).getInt("turns"));
    }
    @Test public void originalAndEachCroppedAnswerImageRemainImmutable()throws Exception{
        String exam=store.create("裁剪"),q=store.addQuestion(exam,new byte[]{1},"截图");String original=question(exam,q).getString("image");String first=store.beginTurn(exam,q,"原图回答","m");store.updateTurn(exam,q,first,"答","complete","");
        String crop1=store.crop(exam,q,new byte[]{2});String second=store.beginTurn(exam,q,"裁剪回答","m");store.updateTurn(exam,q,second,"答二","complete","");String crop2=store.crop(exam,q,new byte[]{3});
        assertNotEquals(crop1,crop2);JSONArray turns=question(exam,q).getJSONArray("turns");assertEquals(original,turns.getJSONObject(0).getString("image"));assertEquals(crop1,turns.getJSONObject(1).getString("image"));assertArrayEquals(new byte[]{2},Files.readAllBytes(store.image(exam,crop1).toPath()));assertArrayEquals(new byte[]{1},Files.readAllBytes(store.image(exam,original).toPath()));
    }
    @Test public void lateStreamingUpdatesCannotOverwriteNewerOrFinalAnswers()throws Exception{
        String exam=store.create("流式"),q=store.addQuestion(exam,null,"文字"),turn=store.beginTurn(exam,q,"题","m");
        assertTrue(store.updateTurn(exam,q,turn,"新片段","generating","",2));assertFalse(store.updateTurn(exam,q,turn,"旧片段","generating","",1));
        assertTrue(store.updateTurn(exam,q,turn,"最终答案","complete","",3));assertFalse(store.updateTurn(exam,q,turn,"迟到片段","generating","",4));assertEquals("最终答案",question(exam,q).getJSONArray("turns").getJSONObject(0).getString("answer"));
    }
    @Test public void interruptionAndFailuresPreservePartialTextAndImage()throws Exception{
        String exam=store.create("中断"),q=store.addQuestion(exam,new byte[]{7},"截图"),turn=store.beginTurn(exam,q,"题","m");store.updateTurn(exam,q,turn,"已有部分答案","generating","");store=new ExamStore(root);store.recoverInterrupted();JSONObject saved=store.read(exam),t=question(exam,q).getJSONArray("turns").getJSONObject(0);assertEquals("interrupted",saved.getString("status"));assertEquals("interrupted",t.getString("status"));assertEquals("已有部分答案",t.getString("answer"));
        try{store.addQuestion(exam,null,"文字");fail("Ended exam accepted new question");}catch(IOException expected){}
        String second=store.create("下一场"),q2=store.addQuestion(second,null,"文字"),t2=store.beginTurn(second,q2,"题2","m");store.updateTurn(second,q2,t2,"","failed","HTTP 429");assertEquals("HTTP 429",question(second,q2).getJSONArray("turns").getJSONObject(0).getString("error"));assertEquals(1,store.read(exam).getJSONArray("questions").length());
    }
    @Test public void legacyMigrationIsIdempotentAndDeletedDataIsNotReimported()throws Exception{
        JSONArray legacy=new JSONArray().put(new JSONObject().put("at","旧时间").put("question","旧题").put("answer","旧答案"));store.importLegacy(legacy);store.importLegacy(legacy);assertEquals(1,store.list().size());String id=store.list().get(0).getString("id");assertTrue(ExamStore.exportText(store.read(id)).contains("旧答案"));store.delete(id);store.importLegacy(legacy);assertTrue(store.list().isEmpty());
    }
    @Test public void recordsBeyondThirtyStayAndDeletionIsConfinedToOneRecord()throws Exception{
        for(int i=0;i<35;i++){String id=store.create("笔试 "+i);store.finish(id);}assertEquals(35,store.list().size());String id=store.list().get(0).getString("id");store.delete(id);assertEquals(34,store.list().size());
        try{store.delete("../outside");fail("Path traversal accepted");}catch(IOException expected){}String other=store.list().get(0).getString("id");try{store.image(other,"../secret");fail("Image traversal accepted");}catch(IOException expected){}
    }
    @Test public void finishedExamCanResumeWithoutReplacingQuestionsImagesOrAnswers()throws Exception{
        String id=store.create("可继续笔试"),q=store.addQuestion(id,new byte[]{3,4},"截图"),t=store.beginTurn(id,q,"原题","mock");
        store.updateTurn(id,q,t,"旧答案","complete","");long created=store.read(id).getLong("createdAt");store.finish(id);
        store=new ExamStore(root);JSONObject resumed=store.resume(id);assertEquals("active",resumed.getString("status"));assertEquals(created,resumed.getLong("createdAt"));assertEquals(0,resumed.getLong("endedAt"));
        assertEquals("旧答案",question(id,q).getJSONArray("turns").getJSONObject(0).getString("answer"));
        String follow=store.beginTurn(id,q,"继续追问","mock");store.updateTurn(id,q,follow,"新解释","complete","");store.addQuestion(id,null,"新文字题");
        assertEquals(2,store.read(id).getJSONArray("questions").length());assertEquals(2,question(id,q).getJSONArray("turns").length());
        assertArrayEquals(new byte[]{3,4},Files.readAllBytes(store.image(id,question(id,q).getString("image")).toPath()));
        assertFalse(store.updateTurn(id,q,t,"错误覆盖","generating",""));store.finish(id);assertEquals(1,store.list().size());
    }
    @Test public void interruptedExamRestoresSelectedQuestionAndRecentConversation()throws Exception{
        String id=store.create("中断继续"),first=store.addQuestion(id,null,"文字"),last=store.addQuestion(id,null,"文字");
        for(int i=0;i<5;i++){String t=store.beginTurn(id,first,"追问"+i,"m");store.updateTurn(id,first,t,"回答"+i,"complete","");}
        String pending=store.beginTurn(id,last,"中断题","m");store.updateTurn(id,last,pending,"部分答案","generating","");store=new ExamStore(root);store.recoverInterrupted();
        JSONObject resumed=store.resume(id);assertEquals(first,ExamStore.selectQuestion(resumed,first).getString("id"));assertEquals(last,ExamStore.selectQuestion(resumed,"").getString("id"));
        JSONArray history=ExamStore.conversation(ExamStore.selectQuestion(resumed,first));assertEquals(6,history.length());assertEquals("追问2",history.getJSONObject(0).getString("content"));assertEquals("回答4",history.getJSONObject(5).getString("content"));
        assertEquals("interrupted",question(id,last).getJSONArray("turns").getJSONObject(0).getString("status"));assertEquals("部分答案",ExamStore.conversation(question(id,last)).getJSONObject(1).getString("content"));
        try{ExamStore.selectQuestion(resumed,"missing-question");fail("Missing question accepted");}catch(IOException expected){}
    }
    @Test public void emptyAndLegacyRecordsCanContinueAsTheSameExam()throws Exception{
        String empty=store.create("空白笔试");store.finish(empty);JSONObject resumed=store.resume(empty);assertNull(ExamStore.selectQuestion(resumed,""));assertEquals(0,ExamStore.conversation(null).length());store.addQuestion(empty,null,"补充题目");
        store.importLegacy(new JSONArray().put(new JSONObject().put("question","旧题").put("answer","旧答案")));String legacy=store.list().stream().filter(item->"旧作答记录".equals(item.optString("title"))).findFirst().get().getString("id");
        JSONObject selected=ExamStore.selectQuestion(store.resume(legacy),"");assertEquals("旧答案",ExamStore.conversation(selected).getJSONObject(1).getString("content"));assertEquals(2,store.list().size());
    }
}
